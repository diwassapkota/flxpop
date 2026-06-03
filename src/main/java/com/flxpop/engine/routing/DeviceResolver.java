package com.flxpop.engine.routing;

import com.flxpop.engine.domain.Device;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Decides whether to render an app intent (mobile) or a scan-to-pay QR (desktop).
 *
 * Strictly server-side. The FP-SPEC-001 `device_hint` request field is *not*
 * honored here — merchants set it inconsistently and trusting it leads to
 * desktop users being shown a deep-link they can't follow.
 *
 * Heuristic, in order:
 *   1. Sec-CH-UA-Mobile (modern client hints): "?1" → MOBILE, "?0" → DESKTOP.
 *   2. User-Agent contains a mobile token (Mobi, Android, iPhone, iPad pre-iPadOS).
 *   3. Default DESKTOP.
 */
@Component
public class DeviceResolver {

    private static final Pattern MOBILE_UA = Pattern.compile(
            "(?i)mobi|android|iphone|ipod|ipad(?!.*Macintosh)|blackberry|iemobile|opera mini");

    public Device resolve(HttpServletRequest request) {
        String chMobile = request.getHeader("Sec-CH-UA-Mobile");
        if ("?1".equals(chMobile)) return Device.MOBILE;
        if ("?0".equals(chMobile)) return Device.DESKTOP;

        String ua = request.getHeader("User-Agent");
        if (ua != null && MOBILE_UA.matcher(ua).find()) {
            return Device.MOBILE;
        }
        return Device.DESKTOP;
    }
}
