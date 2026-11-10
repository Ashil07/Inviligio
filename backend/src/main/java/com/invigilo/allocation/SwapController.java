package com.invigilo.allocation;

import com.invigilo.allocation.SwapService.MySwaps;
import com.invigilo.allocation.SwapService.SwapCheck;
import com.invigilo.allocation.SwapService.SwapInput;
import com.invigilo.allocation.SwapService.SwapOptions;
import com.invigilo.allocation.SwapService.SwapView;
import com.invigilo.tenant.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Duty swaps (M13): faculty ask and answer under /me; the HOD or exam cell decide under /swap-requests. */
@RestController
@RequestMapping("/api/v1")
public class SwapController {

    /** The version the caller loaded, and an optional note. */
    public record Decision(Long version, String note) {}

    private final SwapService swaps;

    public SwapController(SwapService swaps) {
        this.swaps = swaps;
    }

    @GetMapping("/me/swap-options")
    public SwapOptions options(@RequestParam UUID assignmentId) {
        return swaps.options(CurrentUser.require(), assignmentId);
    }

    @PostMapping("/me/swap-requests/validate")
    public SwapCheck validate(@RequestBody SwapInput input) {
        return swaps.validate(CurrentUser.require(), input);
    }

    @GetMapping("/me/swap-requests")
    public MySwaps mine() {
        return swaps.mine(CurrentUser.require());
    }

    @PostMapping("/me/swap-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public SwapView request(@RequestBody SwapInput input) {
        return swaps.request(CurrentUser.require(), input);
    }

    @PostMapping("/swap-requests/{id}/accept")
    public SwapView accept(@PathVariable UUID id, @RequestBody Decision body) {
        return swaps.accept(CurrentUser.require(), id, body.version(), body.note());
    }

    @PostMapping("/swap-requests/{id}/decline")
    public SwapView decline(@PathVariable UUID id, @RequestBody Decision body) {
        return swaps.decline(CurrentUser.require(), id, body.version(), body.note());
    }

    @PostMapping("/swap-requests/{id}/cancel")
    public SwapView cancel(@PathVariable UUID id, @RequestBody Decision body) {
        return swaps.cancel(CurrentUser.require(), id, body.version());
    }

    @GetMapping("/swap-requests")
    @PreAuthorize("hasAnyRole('EXAM_CELL_ADMIN', 'HOD')")
    public List<SwapView> list(@RequestParam(required = false) SwapRequest.Status status) {
        return swaps.list(CurrentUser.require(), status);
    }

    @PostMapping("/swap-requests/{id}/approve")
    @PreAuthorize("hasAnyRole('EXAM_CELL_ADMIN', 'HOD')")
    public SwapView approve(@PathVariable UUID id, @RequestBody Decision body) {
        return swaps.approve(CurrentUser.require(), id, body.version(), body.note());
    }

    @PostMapping("/swap-requests/{id}/reject")
    @PreAuthorize("hasAnyRole('EXAM_CELL_ADMIN', 'HOD')")
    public SwapView reject(@PathVariable UUID id, @RequestBody Decision body) {
        return swaps.reject(CurrentUser.require(), id, body.version(), body.note());
    }
}
