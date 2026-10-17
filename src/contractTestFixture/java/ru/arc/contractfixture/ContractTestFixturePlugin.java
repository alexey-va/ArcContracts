package ru.arc.contractfixture;

import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import ru.arc.contracts.api.ArcContractsService;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;

/** CI-only fault-injection bootstrap; the production shadow JAR never includes this source set. */
public final class ContractTestFixturePlugin extends JavaPlugin implements Listener, Supplier<CompletableFuture<Void>> {
    private static final String RUNNER_CLASS = "ru.arc.contractfixture.runner.ContractTestFixtureRunner";
    // The existing replacement-Paper fixture has a 120-second readiness deadline.
    private static final int MAX_READINESS_TICKS = 2400;
    private AutoCloseable runner;
    private RunnerClassLoader runnerLoader;
    private CompletableFuture<Void> readiness = new CompletableFuture<>();
    private BukkitTask readinessCheck;

    @Override
    public void onEnable() {
        readiness = new CompletableFuture<>();
        Plugin contracts = getServer().getPluginManager().getPlugin("ArcContracts");
        if (contracts == null || !contracts.isEnabled()) {
            throw new IllegalStateException("Contract test fixture requires enabled ArcContracts");
        }
        getServer().getPluginManager().registerEvents(this, this);
        checkServiceReady();
        if (!readiness.isDone()) {
            int[] remainingTicks = {MAX_READINESS_TICKS};
            // API 0.1.1 has no service-ready future. Registration itself precedes
            // readyAdmission(), so observe the actual availability predicate.
            readinessCheck = getServer().getScheduler().runTaskTimer(this, () -> {
                checkServiceReady();
                if (!readiness.isDone() && --remainingTicks[0] == 0) {
                    failStartup(new IllegalStateException("ArcContracts did not become available within " + MAX_READINESS_TICKS + " ticks"));
                }
            }, 1L, 1L);
        }
    }

    /** The crash fixture waits for this exact desk/route installation, using only JDK types. */
    @Override
    public CompletableFuture<Void> get() {
        return readiness.copy();
    }

    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent event) {
        if (event.getProvider().getService() == ArcContractsService.class) {
            checkServiceReady();
        }
    }

    private void checkServiceReady() {
        if (!isEnabled() || readiness.isDone()) return;
        try {
            ArcContractsService service = getServer().getServicesManager().load(ArcContractsService.class);
            if (service == null || !service.isAvailable()) return;
            stopChecking();
            startRunner();
            readiness.complete(null);
        } catch (Throwable failure) {
            failStartup(failure);
        }
    }

    private void startRunner() {
        Plugin contracts = Objects.requireNonNull(getServer().getPluginManager().getPlugin("ArcContracts"));
        URL fixtureJar = Objects.requireNonNull(
                ContractTestFixturePlugin.class.getProtectionDomain().getCodeSource(),
                "Fixture JAR code source is unavailable")
                .getLocation();
        runnerLoader = new RunnerClassLoader(fixtureJar, contracts.getClass().getClassLoader());
        try {
            Class<?> runnerType = runnerLoader.loadClass(RUNNER_CLASS);
            Object instance = runnerType.getConstructor(JavaPlugin.class).newInstance(this);
            if (instance.getClass().getClassLoader() != runnerLoader) {
                throw new IllegalStateException("Fixture runner resolved outside its private classloader");
            }
            if (!(instance instanceof Runnable) || !(instance instanceof AutoCloseable)) {
                throw new IllegalStateException("Fixture runner must expose only the Runnable/AutoCloseable seam");
            }
            runner = (AutoCloseable) instance;
            ((Runnable) instance).run();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            closeRunner();
            throw new IllegalStateException("Could not start isolated CI contract test fixture", failure);
        }
    }

    @Override
    public void onDisable() {
        stopChecking();
        readiness.cancel(false);
        closeRunner();
    }

    private void stopChecking() {
        HandlerList.unregisterAll((Listener) this);
        if (readinessCheck != null) {
            readinessCheck.cancel();
            readinessCheck = null;
        }
    }

    private void failStartup(Throwable failure) {
        readiness.completeExceptionally(failure);
        stopChecking();
        closeRunner();
        getLogger().log(Level.SEVERE, "Could not prepare isolated CI contract test fixture", failure);
        getServer().getPluginManager().disablePlugin(this);
    }

    private void closeRunner() {
        if (runner != null) {
            try {
                runner.close();
            } catch (Exception failure) {
                getLogger().severe("Could not close contract test fixture runner: " + failure.getMessage());
            } finally {
                runner = null;
            }
        }
        if (runnerLoader != null) {
            try {
                runnerLoader.close();
            } catch (IOException failure) {
                getLogger().severe("Could not close contract test fixture classloader: " + failure.getMessage());
            } finally {
                runnerLoader = null;
            }
        }
    }

    /**
     * The fixture runner alone is child-first so the legacy Bukkit global class search cannot
     * substitute a fixture copy. Its ArcContracts implementation, Kotlin, and Bukkit dependencies
     * resolve through the actual ArcContracts plugin loader. This private implementation boundary
     * exists only for CI fault injection and must never be copied into a production plugin.
     */
    private static final class RunnerClassLoader extends URLClassLoader {
        private static final String RUNNER_PACKAGE = "ru.arc.contractfixture.runner.";

        private RunnerClassLoader(URL fixtureJar, ClassLoader arcContractsLoader) {
            super(new URL[]{fixtureJar}, arcContractsLoader);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(RUNNER_PACKAGE)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
