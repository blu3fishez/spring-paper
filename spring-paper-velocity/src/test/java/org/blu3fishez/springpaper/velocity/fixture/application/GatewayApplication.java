package org.blu3fishez.springpaper.velocity.fixture.application;

import org.aopalliance.intercept.MethodInterceptor;
import org.blu3fishez.springpaper.velocity.annotation.SpringVelocityApplication;
import org.blu3fishez.springpaper.velocity.fixture.input.GatewayListener;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;

@SpringVelocityApplication(id = "gateway-fixture", name = "Gateway Fixture")
public class GatewayApplication {
    @Bean FactoryBean<GatewayListener> listener(GatewayService service) {
        var factory = new ProxyFactory(new GatewayListener(service));
        factory.setProxyTargetClass(true);
        factory.addAdvice((MethodInterceptor) call -> {
            if (call.getMethod().getName().equals("reload")) service.advice.incrementAndGet();
            return call.proceed();
        });
        GatewayListener product = (GatewayListener) factory.getProxy();
        return new FactoryBean<>() {
            @Override public GatewayListener getObject() { return product; }
            @Override public Class<?> getObjectType() { return GatewayListener.class; }
            @Override public boolean isSingleton() { return true; }
        };
    }

    @Bean ApplicationListener<ApplicationReadyEvent> consumerReady(GatewayService service) {
        return ignored -> service.ready.run();
    }
}
