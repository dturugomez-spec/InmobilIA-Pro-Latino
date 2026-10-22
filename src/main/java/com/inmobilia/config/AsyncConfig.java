package com.inmobilia.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Pool de hilos controlado para todas las tareas @Async (emails, workflows,
 * scoring, seeders...). Sin esto, Spring usa un executor que crea hilos sin
 * límite → riesgo de agotar memoria bajo carga. Aquí lo acotamos.
 */
@Configuration
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    @Override
    @Bean(name = "taskExecutor")
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(4);
        ex.setMaxPoolSize(16);
        ex.setQueueCapacity(200);
        ex.setThreadNamePrefix("ipro-async-");
        ex.setTaskDecorator(com.inmobilia.util.PaisActual::propagar); // emails y avisos con la moneda de la inmobiliaria
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(20);
        // Si la cola se llena, ejecuta en el hilo que llama (no pierde tareas)
        ex.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        ex.initialize();
        return ex;
    }

    /**
     * Pool dedicado para el fan-out de lecturas del Dashboard (consultas
     * independientes a Supabase lanzadas en paralelo). Separado del pool @Async
     * para que las lecturas de la petición no compitan con emails/workflows.
     * CallerRunsPolicy: si se satura, la consulta corre en el hilo de la petición
     * (degrada a secuencial, nunca falla).
     */
    @Bean(name = "dashboardExecutor")
    public Executor dashboardExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(8);
        ex.setMaxPoolSize(24);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("ipro-dash-");
        ex.setTaskDecorator(com.inmobilia.util.PaisActual::propagar);
        ex.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        ex.initialize();
        return ex;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                log.warn("Async error en {}: {}", method.getName(), ex.getMessage());
    }
}
