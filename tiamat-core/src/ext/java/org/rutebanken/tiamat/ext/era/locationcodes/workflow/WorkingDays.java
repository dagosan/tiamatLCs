/*
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 *   https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 *
 * Added for the ERA Location Code Manager, 2026-10-30.
 */

package org.rutebanken.tiamat.ext.era.locationcodes.workflow;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * Agency working days, as the frontend counts them (workflow/workingDays.ts):
 * weekends are skipped; the Agency's holidays are not entered yet.
 */
public final class WorkingDays {

    /** Agency public holidays; empty until the Agency's calendar is entered. */
    static final Set<LocalDate> AGENCY_HOLIDAYS = Set.of();

    private static final int ISO_DATE_LENGTH = 10;

    private WorkingDays() {
    }

    /** The date part of an ISO date-time, e.g. "2026-10-29". */
    public static LocalDate dateOf(String isoDateTime) {
        return LocalDate.parse(isoDateTime.substring(0, ISO_DATE_LENGTH));
    }

    public static boolean isWorkingDay(LocalDate day) {
        DayOfWeek weekday = day.getDayOfWeek();
        return weekday != DayOfWeek.SATURDAY && weekday != DayOfWeek.SUNDAY && !AGENCY_HOLIDAYS.contains(day);
    }

    /** Working days after `from` up to and including `to`: the day of `from` is day 0. */
    public static int between(LocalDate from, LocalDate to) {
        int count = 0;
        for (LocalDate day = from.plusDays(1); !day.isAfter(to); day = day.plusDays(1)) {
            if (isWorkingDay(day)) {
                count++;
            }
        }
        return count;
    }
}
