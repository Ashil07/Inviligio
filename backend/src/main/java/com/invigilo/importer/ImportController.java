package com.invigilo.importer;

import com.invigilo.importer.ImportService.ImportReport;
import java.io.IOException;
import java.util.Locale;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** {@code type} is one of departments, subjects, halls, faculty. Dry run is the default. */
@RestController
@RequestMapping("/api/v1/imports")
@PreAuthorize("hasRole('EXAM_CELL_ADMIN')")
public class ImportController {

    private final ImportService imports;

    public ImportController(ImportService imports) {
        this.imports = imports;
    }

    @PostMapping(value = "/{type}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportReport run(@PathVariable String type, @RequestPart("file") MultipartFile file,
                            @RequestParam(defaultValue = "true") boolean dryRun) throws IOException {
        ImportType importType = ImportType.parse(type).orElseThrow(() -> ImportService.unknownType(type));
        return imports.run(importType, file.getBytes(), dryRun);
    }

    @GetMapping("/{type}/template")
    public ResponseEntity<byte[]> template(@PathVariable String type) {
        ImportType importType = ImportType.parse(type).orElseThrow(() -> ImportService.unknownType(type));
        String filename = "invigilo-" + importType.name().toLowerCase(Locale.ROOT) + "-template.csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(imports.template(importType));
    }
}
