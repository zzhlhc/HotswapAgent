package org.hotswap.agent.plugin.spring.reload;

import org.hotswap.agent.logging.AgentLogger;
import org.hotswap.agent.plugin.spring.listener.SpringEvent;
import org.hotswap.agent.plugin.spring.listener.SpringEventSource;
import org.hotswap.agent.plugin.spring.listener.SpringListener;
import org.hotswap.agent.plugin.spring.scanner.BeanDefinitionChangeEvent;
import org.hotswap.agent.plugin.spring.scanner.ClassChangeEvent;
import org.hotswap.agent.util.spring.util.ObjectUtils;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class SpringChangedAgent implements SpringListener<SpringEvent<?>> {
    private static AgentLogger LOGGER = AgentLogger.getLogger(SpringChangedAgent.class);

    private static final AtomicInteger waitingReloadCount = new AtomicInteger(0);

    private DefaultListableBeanFactory defaultListableBeanFactory;
    private static ClassLoader appClassLoader;
    private static Map<DefaultListableBeanFactory, SpringChangedAgent> springChangeAgents = new ConcurrentHashMap<>(2);
    private final SpringBeanReload springReload;
    ReentrantLock reloadLock = new ReentrantLock();

    public SpringChangedAgent(DefaultListableBeanFactory defaultListableBeanFactory) {
        springReload = new SpringBeanReload(defaultListableBeanFactory);
        this.defaultListableBeanFactory = defaultListableBeanFactory;
        SpringEventSource.INSTANCE.addListener(this);
    }

    public static SpringChangedAgent getInstance(DefaultListableBeanFactory beanFactory) {
        if (springChangeAgents.get(beanFactory) == null) {
            synchronized (SpringChangedAgent.class) {
                if (springChangeAgents.get(beanFactory) == null) {
                    SpringChangedAgent springChangedAgent = new SpringChangedAgent(beanFactory);
                    springChangeAgents.put(beanFactory, springChangedAgent);
                }
            }
        }
        return springChangeAgents.get(beanFactory);
    }


    public static void setClassLoader(ClassLoader classLoader) {
        SpringChangedAgent.appClassLoader = classLoader;
    }

    public static boolean addChangedClass(Class clazz) {
        boolean result = false;
        for (SpringChangedAgent springChangedAgent : springChangeAgents.values()) {
            result |= springChangedAgent.addClass(clazz);
        }
        return result;
    }

    public static boolean addChangedXml(URL xmlUrl) {
        for (SpringChangedAgent springChangedAgent : springChangeAgents.values()) {
            springChangedAgent.addXml(xmlUrl);
        }
        return true;
    }

    public static boolean addChangedProperty(URL property) {
        for (SpringChangedAgent springChangedAgent : springChangeAgents.values()) {
            springChangedAgent.addProperty(property);
        }
        return true;
    }

    public static void reload(long changeTimeStamps) {
        int reloadCount = waitingReloadCount.incrementAndGet();
        // avoid reload too much times, allow 2 tasks into waiting queue
        if (reloadCount > 2) {
            LOGGER.trace("Spring reload is already scheduled, skip this time:{}", changeTimeStamps);
            waitingReloadCount.decrementAndGet();
            return;
        }
        try {
            for (SpringChangedAgent springChangedAgent : springChangeAgents.values()) {
                // ensure reload only once, there is one lock.
                springChangedAgent.reloadAll(changeTimeStamps);
            }
        } finally {
            waitingReloadCount.decrementAndGet();
        }
    }

    /**
     * unit test
     * @param beanFactory
     */
    public static void destroyBeanFactory(AbstractAutowireCapableBeanFactory beanFactory) {
        if (!(beanFactory instanceof DefaultListableBeanFactory)) {
            return;
        }
        springChangeAgents.remove(beanFactory);
    }

    boolean addClass(Class clazz) {
        if (clazz == null) {
            return false;
        }
        springReload.addClass(clazz);
        return true;
    }

    void addProperty(URL property) {
        springReload.addProperty(property);
    }

    void addXml(URL xml) {
        springReload.addXml(xml);
    }

    void addNewBean(BeanDefinitionRegistry registry, BeanDefinitionHolder beanDefinitionHolder) {
        springReload.addScanNewBean(registry, beanDefinitionHolder);
    }

    private void reloadAll(long changeTimeStamps) {
        try {
            doReload(changeTimeStamps);
        } catch (InterruptedException e) {
            LOGGER.warning("reload spring failed: {}", e, ObjectUtils.identityToString(defaultListableBeanFactory));
        }
    }

    private void doReload(long changeTimeStamps) throws InterruptedException {
        boolean isLockAcquired = reloadLock.tryLock(1, TimeUnit.SECONDS);
        if (isLockAcquired) {
            try {
                LOGGER.trace("Spring reload: {} at timestamps '{}'", ObjectUtils.identityToString(defaultListableBeanFactory), changeTimeStamps);
                springReload.reload(changeTimeStamps);
            } finally {
                reloadLock.unlock();
            }
        } else {
            Thread.sleep(100);
            doReload(changeTimeStamps);
        }
    }

    public static void collectPlaceholderProperties(ConfigurableListableBeanFactory configurableListableBeanFactory) {
        if (!(configurableListableBeanFactory instanceof DefaultListableBeanFactory)) {
            return;
        }
        getInstance((DefaultListableBeanFactory) configurableListableBeanFactory).springReload.collectPlaceHolderProperties();
    }


    @Override
    public DefaultListableBeanFactory beanFactory() {
        return defaultListableBeanFactory;
    }

    @Override
    public void onEvent(SpringEvent<?> event) {
        if (event instanceof BeanDefinitionChangeEvent) {
            BeanDefinitionChangeEvent beanDefinitionChangeEvent = (BeanDefinitionChangeEvent) event;
            addNewBean(beanDefinitionChangeEvent.getBeanFactory(), beanDefinitionChangeEvent.getSource());
        } else if (event instanceof ClassChangeEvent) {
            ClassChangeEvent changeEvent = (ClassChangeEvent) event;
            addClass(changeEvent.getSource());
        }
    }
}
