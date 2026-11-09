package com.invigilo.allocation;

import com.invigilo.allocation.MyDutiesService.MyDuties;
import com.invigilo.tenant.CurrentUser;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in faculty member's own published duties (faculty dashboard, M11). */
@RestController
@RequestMapping("/api/v1/me")
public class MyDutiesController {

    private final MyDutiesService duties;

    public MyDutiesController(MyDutiesService duties) {
        this.duties = duties;
    }

    @GetMapping("/duties")
    public MyDuties mine() {
        return duties.mine(CurrentUser.require());
    }

    /** All published duties as a calendar file (.ics) to import into Google Calendar, Outlook and the like. */
    @GetMapping("/duties.ics")
    public ResponseEntity<byte[]> calendar() {
        byte[] body = duties.calendar(CurrentUser.require()).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "calendar", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("invigilation-duties.ics").build().toString())
                .body(body);
    }
}
