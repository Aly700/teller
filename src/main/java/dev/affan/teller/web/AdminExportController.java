package dev.affan.teller.web;

import dev.affan.teller.export.AuditExportResult;
import dev.affan.teller.export.AuditExportService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/exports")
public class AdminExportController {

    private final AuditExportService auditExportService;

    public AdminExportController(AuditExportService auditExportService) {
        this.auditExportService = auditExportService;
    }

    @PostMapping("/audit")
    AuditExportResult exportAudit(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return auditExportService.export(date);
    }
}
