package com.factchecker.claim;

import com.factchecker.domain.TemporalStatus;
import com.factchecker.factcheck.SourceEvaluation;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Year;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static com.factchecker.factcheck.SourceEvaluation.Stance;

/**
 * Classifies whether a claim's validity is time-bound, deterministically from the dates and
 * stances of the sources already retrieved - no extra LLM call. This directly answers the spec's
 * "was this historically supported but now outdated" question: if older evidence leans one way and
 * newer evidence leans the other, the conclusion has changed over time.
 */
@Service
public class TemporalAnalysisService {

    private static final int RECENT_YEARS = 3;

    public TemporalStatus classify(boolean timeSensitive, List<SourceEvaluation> relevantSources) {
        if (!timeSensitive) return TemporalStatus.NOT_TIME_SENSITIVE;

        List<DatedSource> dated = relevantSources.stream()
                .map(this::parseDate)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .sorted(Comparator.comparing(DatedSource::date))
                .toList();

        if (dated.isEmpty()) return TemporalStatus.TIME_SENSITIVE_UNVERIFIED;

        int currentYear = Year.now().getValue();
        boolean anyRecent = dated.stream().anyMatch(d -> (currentYear - d.date().getYear()) <= RECENT_YEARS);
        if (!anyRecent) return TemporalStatus.TIME_SENSITIVE_UNVERIFIED;

        // Compare the leaning of the older half of dated sources against the newer half - if they
        // disagree, the newer evidence is treated as changing the conclusion.
        int mid = dated.size() / 2;
        if (dated.size() >= 2) {
            Stance olderLeaning = dominantStance(dated.subList(0, Math.max(1, mid)));
            Stance newerLeaning = dominantStance(dated.subList(mid, dated.size()));
            if (olderLeaning != null && newerLeaning != null && olderLeaning != newerLeaning
                    && olderLeaning != Stance.NOT_RELEVANT && newerLeaning != Stance.NOT_RELEVANT) {
                return TemporalStatus.HISTORICAL_OUTDATED;
            }
        }

        return TemporalStatus.CURRENT;
    }

    private Stance dominantStance(List<DatedSource> sources) {
        return sources.stream()
                .map(DatedSource::source)
                .map(SourceEvaluation::stance)
                .filter(s -> s != Stance.NOT_RELEVANT)
                .findFirst()
                .orElse(null);
    }

    private Optional<DatedSource> parseDate(SourceEvaluation source) {
        String raw = source.publishedDate();
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            String normalized = raw.length() > 10 ? raw.substring(0, 10) : raw;
            return Optional.of(new DatedSource(LocalDate.parse(normalized), source));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private record DatedSource(LocalDate date, SourceEvaluation source) {
    }
}
