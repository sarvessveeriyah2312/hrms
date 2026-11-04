package com.hrms.attendance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PunchRepository extends JpaRepository<Punch, UUID> {

    List<Punch> findByEmployeeIdAndServerTimeBetweenOrderByServerTimeDesc(UUID employeeId, Instant from, Instant to);
}
