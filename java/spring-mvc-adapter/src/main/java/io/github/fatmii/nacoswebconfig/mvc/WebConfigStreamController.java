package io.github.fatmii.nacoswebconfig.mvc;

import java.security.Principal;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("${nacos-web-config.path:/_web-config}/v1")
final class WebConfigStreamController {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private final WebConfigStreamService streams;
    private final WebConfigAccessPolicy accessPolicy;
    private final int maxKeys;

    WebConfigStreamController(
            WebConfigStreamService streams,
            WebConfigAccessPolicy accessPolicy,
            int maxKeys) {
        this.streams = streams;
        this.accessPolicy = accessPolicy;
        this.maxKeys = maxKeys;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> stream(
            @RequestParam(value = "key", required = false) List<String> keys,
            Principal principal) {
        if (!accessPolicy.permits(principal)) {
            throw WebConfigRequestException.unauthenticated();
        }
        var normalized = validate(keys);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(streams.open(normalized));
    }

    private List<String> validate(List<String> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > maxKeys) {
            throw WebConfigRequestException.invalid();
        }
        var unique = new HashSet<String>();
        var normalized = new ArrayList<String>();
        for (var key : keys) {
            if (key == null || !KEY.matcher(key).matches() || !unique.add(key)) {
                throw WebConfigRequestException.invalid();
            }
            normalized.add(key);
        }
        normalized.sort(String::compareTo);
        streams.validate(normalized);
        return normalized;
    }
}
