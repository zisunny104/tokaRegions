package dev.toka.regions.link;

import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Region accounts in tokaEconomy, reached reflectively. An account's id is the region's id. */
public final class EconomyLink {
    private final Server server;
    private final String currency;

    public EconomyLink(Server server, String currency) {
        this.server = server;
        this.currency = currency;
    }

    public String currency() { return currency; }

    private Class<?> api() throws ReflectiveOperationException {
        Plugin economy = server.getPluginManager().getPlugin("tokaEconomy");
        if (economy == null || !economy.isEnabled()) throw new IllegalStateException("ECONOMY_UNAVAILABLE");
        return Class.forName("dev.toka.economy.Economy", true, economy.getClass().getClassLoader());
    }

    private static String status(Object result) throws ReflectiveOperationException {
        return result.getClass().getMethod("status").invoke(result).toString();
    }

    /** Makes the account if it is missing (harmless if it exists). Returns whether the economy is there to hold it. */
    public boolean ensureAccount(UUID id, String name) {
        try {
            Class<?> api = api();
            Object result = api.getMethod("createAccount", UUID.class, String.class).invoke(null, id, name);
            return status(result).equals("OK");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    public Optional<Long> balance(UUID id) {
        try {
            Class<?> api = api();
            Object result = api.getMethod("balance", UUID.class, String.class).invoke(null, id, currency);
            if (!status(result).equals("OK")) return Optional.empty();
            return Optional.of((Long) result.getClass().getMethod("balance").invoke(result));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return Optional.empty();
        }
    }

    /** One durable, fee-free transfer; the operation id makes a retry harmless. Completes with the economy's status. */
    @SuppressWarnings("unchecked")
    public CompletableFuture<String> transfer(UUID from, UUID to, long amount, String operationId) {
        try {
            Class<?> api = api();
            Class<?> cause = Class.forName("dev.toka.economy.Cause", true, api.getClassLoader());
            Object why = cause.getMethod("of", String.class, String.class).invoke(null, "region-treasury", "tokaRegions");
            CompletableFuture<Object> work = (CompletableFuture<Object>) api.getMethod("transferOnce", UUID.class, UUID.class, String.class,
                    long.class, cause, String.class).invoke(null, from, to, currency, amount, why, operationId);
            return work.thenApply(result -> {
                try { return status(result); } catch (ReflectiveOperationException failure) { throw new java.util.concurrent.CompletionException(failure); }
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return CompletableFuture.failedFuture(unavailable);
        }
    }
}
