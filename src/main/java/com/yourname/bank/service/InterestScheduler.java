package com.yourname.bank.service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background thread that credits monthly interest on a fixed schedule.
 * Uses a daemon thread so it never stops the program from exiting.
 */
public class InterestScheduler {
    private static final Logger LOG = Logger.getLogger(InterestScheduler.class.getName());

    private final BankService bank;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "interest-scheduler");
        t.setDaemon(true);
        return t;
    });

    public InterestScheduler(BankService bank) { this.bank = bank; }

    public void start(long initialDelay, long period, TimeUnit unit) {
        executor.scheduleAtFixedRate(() -> {
            try {
                bank.applyMonthlyInterest();
            } catch (RuntimeException e) {
                // an uncaught exception would silently cancel all future runs
                LOG.log(Level.SEVERE, "Interest run failed", e);
            }
        }, initialDelay, period, unit);
    }

    public void stop() { executor.shutdownNow(); }
}
