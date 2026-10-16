package com.ibm.liberty.admin.internal;

import com.ibm.wsspi.wab.configure.WABConfiguration;
import java.util.Map;
import java.util.logging.Logger;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;

/**
 * Configuration for the console, set in server.xml as
 * {@code <usr_console contextPath="/console"/>}. The attribute has a default, so
 * the element can be left out.
 * <p>
 * The component is registered as a {@link WABConfiguration} service. Liberty uses
 * its {@code contextName}/{@code contextPath} service properties to deploy the
 * bundle's web module, whose {@code Web-ContextPath: @libertyAdminConsole}
 * header marks the path as configurable. When contextPath changes, Liberty
 * redeploys the web module at the new path.
 */
@Component(
    configurationPid = ConsoleConfiguration.PID,
    configurationPolicy = ConfigurationPolicy.REQUIRE,
    service = WABConfiguration.class,
    immediate = true,
    property = { "service.vendor=IBM" })
public class ConsoleConfiguration implements WABConfiguration {

    public static final String PID = "com.ibm.liberty.admin.console";

    private static final Logger LOGGER = Logger.getLogger(ConsoleConfiguration.class.getName());

    private BundleContext bundleContext;

    @Activate
    protected void activate(BundleContext context, Map<String, Object> properties) {
        this.bundleContext = context;
        publish(properties);
    }

    @Modified
    protected void modified(Map<String, Object> properties) {
        publish(properties);
    }

    @Deactivate
    protected void deactivate() {
        ConsoleRuntime.set(null);
        bundleContext = null;
    }

    private void publish(Map<String, Object> properties) {
        BundleContext context = bundleContext;
        ConsoleRuntime runtime = ConsoleRuntime.create(
            key -> context != null ? context.getProperty(key) : System.getProperty(key),
            System::getenv,
            properties);
        ConsoleRuntime.set(runtime);
        LOGGER.fine(() -> "Liberty Admin Console configured: contextPath=" + runtime.getContextPath()
            + ", serverConfigDir=" + runtime.getServerConfigDir() + ", logDir=" + runtime.getLogDir());
    }
}
