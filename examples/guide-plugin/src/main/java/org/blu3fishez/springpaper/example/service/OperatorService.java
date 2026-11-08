package org.blu3fishez.springpaper.example.service;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class OperatorService {
    @PreAuthorize("hasRole('CONSOLE')")
    public String verifyConsole() { return "console-authorized"; }
}
