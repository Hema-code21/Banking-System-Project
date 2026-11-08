package com.yourname.bank.model;

import java.math.BigDecimal;

public class CurrentAccount extends Account {
    private static final BigDecimal OVERDRAFT_LIMIT = new BigDecimal("5000");

    public CurrentAccount(long no, String name, String salt, String hash, BigDecimal opening) {
        super(no, name, salt, hash, opening);
    }

    @Override protected BigDecimal minimumAllowedBalance() { return OVERDRAFT_LIMIT.negate(); }
    @Override public String getType() { return "Current"; }
}
