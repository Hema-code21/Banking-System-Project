package com.yourname.bank.model;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * A fixed-rate loan repaid in equal monthly instalments (EMI).
 * Changes to a loan are made while the owner's account lock is held (see BankService).
 */
public class Loan {
    public enum Status { ACTIVE, CLOSED }

    private final long loanId;
    private final long accountNumber;
    private final BigDecimal principal;
    private final BigDecimal annualRatePercent;
    private final int months;
    private final BigDecimal emi;
    private final LocalDate startDate;

    private volatile BigDecimal outstanding;   // total still to be paid (principal + interest)
    private volatile Status status;

    public Loan(long loanId, long accountNumber, BigDecimal principal, BigDecimal annualRatePercent,
                int months, BigDecimal emi, BigDecimal outstanding, Status status, LocalDate startDate) {
        this.loanId = loanId;
        this.accountNumber = accountNumber;
        this.principal = principal;
        this.annualRatePercent = annualRatePercent;
        this.months = months;
        this.emi = emi;
        this.outstanding = outstanding;
        this.status = status;
        this.startDate = startDate;
    }

    /** EMI = P * r * (1+r)^n / ((1+r)^n - 1), where r is the monthly rate. */
    public static BigDecimal calculateEmi(BigDecimal principal, BigDecimal annualRatePercent, int months) {
        if (annualRatePercent.signum() == 0) {
            return principal.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
        }
        MathContext mc = MathContext.DECIMAL64;
        BigDecimal r = annualRatePercent.divide(BigDecimal.valueOf(1200), mc);
        BigDecimal pow = BigDecimal.ONE.add(r).pow(months, mc);
        return principal.multiply(r).multiply(pow)
                .divide(pow.subtract(BigDecimal.ONE), mc)
                .setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal totalPayable(BigDecimal emi, int months) {
        return emi.multiply(BigDecimal.valueOf(months));
    }

    public Loan withId(long newId) {
        return new Loan(newId, accountNumber, principal, annualRatePercent, months, emi,
                outstanding, status, startDate);
    }

    /** Called after the payment has been saved to the database. */
    public void applyPayment(BigDecimal paid) {
        BigDecimal left = outstanding.subtract(paid);
        outstanding = left;
        if (left.signum() <= 0) status = Status.CLOSED;
    }

    public long getLoanId() { return loanId; }
    public long getAccountNumber() { return accountNumber; }
    public BigDecimal getPrincipal() { return principal; }
    public BigDecimal getAnnualRatePercent() { return annualRatePercent; }
    public int getMonths() { return months; }
    public BigDecimal getEmi() { return emi; }
    public BigDecimal getOutstanding() { return outstanding; }
    public Status getStatus() { return status; }
    public LocalDate getStartDate() { return startDate; }

    @Override
    public String toString() {
        return String.format("Loan #%d | principal %s @ %s%% for %d months | EMI %s | outstanding %s | %s",
                loanId, principal, annualRatePercent, months, emi, outstanding, status);
    }
}
