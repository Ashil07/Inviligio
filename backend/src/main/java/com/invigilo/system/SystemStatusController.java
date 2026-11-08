package com.invigilo.system;

import com.invigilo.system.SystemStatusService.SystemStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, unauthenticated connectivity check used by the frontend and deployment probes. */
@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {

    private final SystemStatusService service;

    public SystemStatusController(SystemStatusService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public SystemStatus status() {
        return service.status();
    }
}
