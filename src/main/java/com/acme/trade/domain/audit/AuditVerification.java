package com.acme.trade.domain.audit;

public record AuditVerification(boolean valid, int checkedEvents, String message) {
}
