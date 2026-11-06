package com.hrms.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Choosing one workflow for a claim or leave type, from the type's settings. */
class ApprovalFlowsAssignTests {

    private static final UUID TRAVEL = UUID.randomUUID();
    private static final UUID MEALS = UUID.randomUUID();
    private static final UUID SALES = UUID.randomUUID();

    private static ApprovalFlows.Flow flow(String name, boolean active, int position, List<UUID> types, List<UUID> depts, BigDecimal min) {
        return new ApprovalFlows.Flow(UUID.randomUUID(), "CLAIM", name, null, active, position, types, depts, min, null, List.of(), null);
    }

    private static ApprovalFlows.Flow flow(String name, int position, List<UUID> types) {
        return flow(name, true, position, types, List.of(), null);
    }

    private static List<String> kinds(List<ApprovalFlows.AssignChange> changes) {
        return changes.stream().map(c -> c.kind() + ":" + c.flow()).toList();
    }

    @Test
    void aSpecificWorkflowBelowACatchAllIsAddedToAndMovedAboveIt() {
        var monthly = flow("Monthly", 0, List.of());
        var travel = flow("Travel", 1, List.of(MEALS));
        var plan = ApprovalFlows.planAssign(List.of(monthly, travel), travel.id(), TRAVEL);

        assertThat(kinds(plan.changes())).containsExactly("ADD_TYPE:Travel", "MOVE_ABOVE:Monthly");
        assertThat(plan.flowTypes()).containsExactly(MEALS, TRAVEL);
        assertThat(plan.order()).containsExactly(travel.id(), monthly.id());
        assertThat(kinds(plan.warnings())).containsExactly("OTHER_TYPES:Travel");   // Meals claims now reach it first too
    }

    @Test
    void aWorkflowAboveThatListsTheTypeAmongOthersLosesIt() {
        var general = flow("General", 0, List.of(TRAVEL, MEALS));
        var travel = flow("Travel", 1, List.of(TRAVEL));
        var plan = ApprovalFlows.planAssign(List.of(general, travel), travel.id(), TRAVEL);

        assertThat(kinds(plan.changes())).containsExactly("REMOVE_TYPE:General");
        assertThat(plan.removals().get(general.id())).containsExactly(MEALS);
        assertThat(plan.order()).containsExactly(general.id(), travel.id());
        assertThat(plan.warnings()).isEmpty();
    }

    @Test
    void aWorkflowAboveForOnlyThisTypeIsPassedAndWontBeUsedForIt() {
        var old = flow("Old travel", 0, List.of(TRAVEL));
        var travel = flow("Travel", 1, List.of(TRAVEL));
        var plan = ApprovalFlows.planAssign(List.of(old, travel), travel.id(), TRAVEL);

        assertThat(kinds(plan.changes())).containsExactly("MOVE_ABOVE:Old travel");
        assertThat(kinds(plan.warnings())).containsExactly("UNUSED:Old travel");
    }

    @Test
    void switchedOffWorkflowsAreSwitchedOnAndOnesAboveThatAreOffDontCount() {
        var off = flow("Off", false, 0, List.of(), List.of(), null);
        var travel = flow("Travel", false, 1, List.of(TRAVEL), List.of(), null);
        var plan = ApprovalFlows.planAssign(List.of(off, travel), travel.id(), TRAVEL);

        assertThat(plan.switchOn()).isTrue();
        assertThat(kinds(plan.changes())).containsExactly("SWITCH_ON:Travel");
        assertThat(plan.order()).containsExactly(off.id(), travel.id());
    }

    @Test
    void alreadyTheOneUsedChangesNothing() {
        var travel = flow("Travel", 0, List.of(TRAVEL));
        var monthly = flow("Monthly", 1, List.of());
        var plan = ApprovalFlows.planAssign(List.of(travel, monthly), travel.id(), TRAVEL);
        assertThat(plan.changes()).isEmpty();
        assertThat(plan.flowTypes()).isNull();
    }

    @Test
    void conditionsMeanItOnlyTakesSomeOfTheType() {
        var monthly = flow("Monthly", 0, List.of());
        var sales = flow("Sales travel", true, 1, List.of(TRAVEL), List.of(SALES), new BigDecimal("500"));
        var plan = ApprovalFlows.planAssign(List.of(monthly, sales), sales.id(), TRAVEL);
        assertThat(kinds(plan.changes())).containsExactly("MOVE_ABOVE:Monthly");
        assertThat(kinds(plan.warnings())).containsExactly("ONLY_SOME:Sales travel");
    }
}
