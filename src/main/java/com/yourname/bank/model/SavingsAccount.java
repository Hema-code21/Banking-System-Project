package com.yourname.bank.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class SavingsAccount extends Account {
    public static final BigDecimal MIN_BALANCE = new BigDecimal("500");
    public static final BigDecimal ANNUAL_INTEREST_RATE = new BigDecimal("0.04"); // 4% per year

    public SavingsAccount(long no, String name, String salt, String hash, BigDecimal opening) {
        super(no, name, salt, hash, opening);
    }

    @Override protected BigDecimal minimumAllowedBalance() { return MIN_BALANCE; }
    @Override public String getType() { return "Savings"; }

    /** One month of interest on the current balance. Call while holding the account lock. */
    public BigDecimal monthlyInterest() {
        return balance.multiply(ANNUAL_INTEREST_RATE).divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
    }
}
