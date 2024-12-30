package sybrix.easygsp2.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class DateUtils {
        static DAY = 1000 * 60 * 60 * 24
        private static TimeZone UTC_TIMEZONE = TimeZone.getTimeZone("UTC");

        public static Date asDate(LocalDate localDate) {
                return Date.from(localDate.atStartOfDay().atZone(ZoneId.systemDefault()).toInstant());
        }

        public static Date asDate(LocalDateTime localDateTime) {
                return Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant());
        }

        public static LocalDate asLocalDate(Date date) {
                return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
        }

        public static LocalDateTime asLocalDateTime(Date date) {
                return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDateTime();
        }

        public static Integer daysSince(Date date) {
                def diff = System.currentTimeMillis() - date.time

                (diff / (1000 * 60 * 60 * 24)).toInteger()
        }
        
        public static Integer minutesSince(Date date) {
                minutesSince(date.time)
        }
        
        public static Integer minutesSince(Long date) {
                def diff = System.currentTimeMillis() - date
                
                (diff / (1000 * 60)).toInteger()
        }
        
        public static Integer diffSeconds(Date date1, Date date2) {
                def diff = date1.time - date2.time

                (diff / (1000)).toInteger()
        }

        static  def diffMinutes(java.sql.Timestamp current, java.sql.Timestamp ts) {
                if (ts == null || current == null){
                        return 0
                }

                def diff = current.time - ts.time
                def _diffMinutes = (diff/1000)/60
                return _diffMinutes
        }
        
        static  def diffHours(Date current, Date ts) {
                if (ts == null || current == null){
                        return 0
                }
                
                def diff = current.time - ts.time
                def _diffHours = (((diff/1000)/60)/60)
                return _diffHours
        }

        static  def diffDays(java.sql.Timestamp current, java.sql.Timestamp ts) {
                if (ts == null || current == null){
                        return -1
                }

                def diff = current.time - ts.time
                def _diffDays = (((diff/1000)/60)/60)/24
                return _diffDays
        }


        public static Long toUTC(Date dt) {
                if (dt == null) {
                        return null;
                }

                Calendar cal = dt.toCalendar()
                cal.setTimeZone(UTC_TIMEZONE)

                return cal.getTime().time
        }

}
