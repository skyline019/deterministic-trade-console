package com.acme.trade.application.port;

import com.acme.trade.application.result.DashboardStats;

public interface DashboardRepository {
    DashboardStats load();
}
