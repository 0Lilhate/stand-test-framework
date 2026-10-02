package ru.alfa.stand.test.eq.backend.gateway;

import ru.alfa.stand.test.http.RestResponse;


@FunctionalInterface
public interface GatewayContracts {

    String confirm(String operation, RestResponse response);
}