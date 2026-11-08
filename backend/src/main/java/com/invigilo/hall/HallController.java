package com.invigilo.hall;

import com.invigilo.common.persistence.Codes;
import com.invigilo.hall.HallService.HallInput;
import com.invigilo.hall.HallService.HallView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/halls")
public class HallController {

    public record HallRequest(
            @NotBlank @Pattern(regexp = Codes.PATTERN, message = Codes.MESSAGE) String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 200) String building,
            @NotNull @Min(1) @Max(2000) Integer capacity,
            @NotNull @Min(1) @Max(20) Integer minInvigilators,
            @NotNull Boolean lab,
            @NotNull Boolean active,
            Long version) {

        HallInput input() {
            return new HallInput(code, name, building, capacity, minInvigilators, lab, active);
        }
    }

    private final HallService halls;

    public HallController(HallService halls) {
        this.halls = halls;
    }

    @GetMapping
    public List<HallView> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return halls.list(includeInactive);
    }

    @GetMapping("/{id}")
    public HallView get(@PathVariable UUID id) {
        return halls.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public HallView create(@Valid @RequestBody HallRequest request) {
        return halls.create(request.input());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public HallView update(@PathVariable UUID id, @Valid @RequestBody HallRequest request) {
        return halls.update(id, request.input(), request.version());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
    public void delete(@PathVariable UUID id) {
        halls.delete(id);
    }
}
