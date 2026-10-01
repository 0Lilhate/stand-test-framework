package ru.alfa.stand.test.eq.backend;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.eq.EqAttribute;
import ru.alfa.stand.test.eq.config.EqBackendConfig;
import ru.alfa.stand.test.eq.config.GatewayBackendConfig;
import ru.alfa.stand.test.eq.config.ShowcasesBackendConfig;

/** Checks requested attributes against the selected backend before any step IO. */
public final class CapabilityMatrix {

    private CapabilityMatrix() {
    }

    /** Returns attributes that will be approximated, or refuses unsupported requirements. */
    public static Set<EqAttribute> validate(SeedPlan plan, EqBackendConfig backend, LocalDate today) {
        if (backend instanceof ShowcasesBackendConfig && "individual".equals(plan.clientKind())) {
            throw new StandTestException("Backend '" + backend.alias()
                    + "' cannot seed an individual through showcases: the record contract is unconfirmed (OQ-2)");
        }
        EnumSet<EqAttribute> unsupported = EnumSet.noneOf(EqAttribute.class);
        for (SeedPlan.Account account : plan.accounts()) {
            if (backend instanceof ShowcasesBackendConfig && account.topUp() != null) {
                unsupported.add(EqAttribute.TOP_UP);
            }
            if (backend instanceof GatewayBackendConfig && account.openedAt() != null
                    && !today.equals(account.openedAt())) {
                unsupported.add(EqAttribute.OPENED_AT);
            }
        }
        EnumSet<EqAttribute> refused = unsupported.clone();
        refused.removeAll(plan.approximations());
        if (!refused.isEmpty()) {
            throw new StandTestException("Backend '" + backend.alias() + "' does not support attributes " + refused
                    + "; allowApproximation is required");
        }
        return Set.copyOf(unsupported);
    }
}
