package com.dbx.dialect.catalog;

import com.dbx.dialect.api.DialectId;
import java.util.ArrayList;
import java.util.List;

/**
 * One endpoint dialect as the catalog recognises it: the product name a connection probe reports and
 * the release series the dialect was written for. Matching is exact; nothing here measures distance.
 *
 * @param product the probed product name, compared case-sensitively and untrimmed
 * @param series the leading version components that name the series, compared as digit strings
 * @param releaseComponents how many numeric components a probed release has for this product
 */
record ReleaseSeries(DialectId id, String product, List<String> series, int releaseComponents) {

    enum Match {
        /** The version is a release of this series. */
        MATCHES,
        /** The version agrees with the series so far but does not name one release, e.g. {@code 8.0}. */
        AMBIGUOUS,
        /** The version is not a release of this series. */
        INCOMPATIBLE
    }

    ReleaseSeries {
        series = List.copyOf(series);
        if (series.isEmpty() || releaseComponents < series.size()) {
            throw new IllegalArgumentException("a series names at most a whole release: " + series);
        }
    }

    /**
     * {@code <digits>(.<digits>)*} followed by nothing or by a suffix starting with {@code -}, {@code +}
     * or a space, as in {@code 8.0.36-log} or {@code 15.4 (Debian 15.4-1)}.
     */
    Match match(String version) {
        List<String> components = new ArrayList<>();
        int position = 0;
        while (true) {
            int start = position;
            while (position < version.length() && isAsciiDigit(version.charAt(position))) {
                position++;
            }
            if (position == start) {
                break;
            }
            components.add(version.substring(start, position));
            if (position + 1 < version.length() && version.charAt(position) == '.'
                    && isAsciiDigit(version.charAt(position + 1))) {
                position++;
            } else {
                break;
            }
        }
        if (components.isEmpty()) {
            return Match.INCOMPATIBLE;
        }
        for (int i = 0; i < Math.min(components.size(), series.size()); i++) {
            if (!components.get(i).equals(series.get(i))) {
                return Match.INCOMPATIBLE;
            }
        }
        if (components.size() < releaseComponents) {
            return Match.AMBIGUOUS;
        }
        if (components.size() > releaseComponents) {
            return Match.INCOMPATIBLE;
        }
        String suffix = version.substring(position);
        if (!suffix.isEmpty() && "-+ ".indexOf(suffix.charAt(0)) < 0) {
            return Match.INCOMPATIBLE;
        }
        return Match.MATCHES;
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
