package com.ibm.liberty.admin.web;

import com.ibm.liberty.admin.internal.ConsoleRuntime;
import com.ibm.liberty.admin.service.ApplicationManagementService;
import com.ibm.liberty.admin.service.ConfigurationService;
import com.ibm.liberty.admin.service.CollectiveService;
import com.ibm.liberty.admin.service.LibertyMBeans;
import com.ibm.liberty.admin.service.LogService;
import com.ibm.liberty.admin.service.ServerManagementService;
import com.ibm.liberty.admin.service.SettingsService;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import java.util.function.Supplier;

/**
 * Creates the console's services when the web module starts and stores them in
 * the ServletContext for the servlets. (CDI is not available in a web
 * application bundle.)
 */
public final class ConsoleServices implements ServletContextListener {

    static final String ATTRIBUTE = ConsoleServices.class.getName();

    private ConfigurationService configuration;
    private ApplicationManagementService applications;
    private ServerManagementService server;
    private LogService logs;
    private SettingsService settings;
    private CollectiveService collective;

    /** Required by the servlet container. */
    public ConsoleServices() {
    }

    private ConsoleServices(Supplier<ConsoleRuntime> runtime, LibertyMBeans mbeans) {
        this.configuration = new ConfigurationService(runtime);
        this.applications = new ApplicationManagementService(configuration, mbeans, runtime);
        this.server = new ServerManagementService(applications, mbeans, runtime);
        this.logs = new LogService(runtime);
        this.settings = new SettingsService(runtime);
        this.collective = new CollectiveService();
    }

    @Override
    public void contextInitialized(ServletContextEvent event) {
        event.getServletContext().setAttribute(ATTRIBUTE, new ConsoleServices(ConsoleRuntime::get, new LibertyMBeans()));
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        event.getServletContext().removeAttribute(ATTRIBUTE);
    }

    static ConsoleServices from(ServletContext context) {
        Object services = context.getAttribute(ATTRIBUTE);
        if (!(services instanceof ConsoleServices)) {
            throw new IllegalStateException("Console services are not initialized");
        }
        return (ConsoleServices) services;
    }

    ConfigurationService configuration() {
        return configuration;
    }

    ApplicationManagementService applications() {
        return applications;
    }

    ServerManagementService server() {
        return server;
    }

    LogService logs() {
        return logs;
    }

    SettingsService settings() {
        return settings;
    }

    CollectiveService collective() {
        return collective;
    }
}
