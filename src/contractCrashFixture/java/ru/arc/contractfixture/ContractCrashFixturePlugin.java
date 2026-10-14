package ru.arc.contractfixture;

import org.bukkit.command.CommandExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Objects;

/** CI-only fault-injection bootstrap; the production shadow JAR never includes this source set. */
public final class ContractCrashFixturePlugin extends JavaPlugin {
    private static final String RUNNER_CLASS = "ru.arc.contractfixture.runner.ContractCrashFixtureRunner";
    private AutoCloseable runner;
    private RunnerClassLoader runnerLoader;

    @Override
    public void onEnable() {
        Plugin contracts = getServer().getPluginManager().getPlugin("ArcContracts");
        if (contracts == null || !contracts.isEnabled()) {
            throw new IllegalStateException("Contract crash fixture requires enabled ArcContracts");
        }
        URL fixtureJar = Objects.requireNonNull(
                ContractCrashFixturePlugin.class.getProtectionDomain().getCodeSource(),
                "Fixture JAR code source is unavailable")
                .getLocation();
        runnerLoader = new RunnerClassLoader(fixtureJar, contracts.getClass().getClassLoader());
        try {
            Class<?> runnerType = runnerLoader.loadClass(RUNNER_CLASS);
            Object instance = runnerType.getConstructor(JavaPlugin.class).newInstance(this);
            if (instance.getClass().getClassLoader() != runnerLoader) {
                throw new IllegalStateException("Fixture runner resolved outside its private classloader");
            }
            if (!(instance instanceof Runnable) || !(instance instanceof CommandExecutor) || !(instance instanceof AutoCloseable)) {
                throw new IllegalStateException("Crash fixture runner must expose only the Runnable/CommandExecutor/AutoCloseable seam");
            }
            runner = (AutoCloseable) instance;
            ((Runnable) instance).run();
            Objects.requireNonNull(getCommand("contractcrashfixture"), "Crash fixture command is missing from plugin.yml")
                    .setExecutor((CommandExecutor) instance);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            closeRunner();
            throw new IllegalStateException("Could not start isolated CI contract crash fixture", failure);
        }
    }

    @Override
    public void onDisable() {
        closeRunner();
    }

    private void closeRunner() {
        if (runner != null) {
            try {
                runner.close();
            } catch (Exception failure) {
                getLogger().severe("Could not close contract crash fixture runner: " + failure.getMessage());
            } finally {
                runner = null;
            }
        }
        if (runnerLoader != null) {
            try {
                runnerLoader.close();
            } catch (IOException failure) {
                getLogger().severe("Could not close contract crash fixture classloader: " + failure.getMessage());
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
