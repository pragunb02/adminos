-- AOS-716 (D-140): the optional AI wording of a briefing, stored next to the template.
-- `content` stays the deterministic template (the plain numbers); the narration is shown only when it passed the
-- number and name check. Codes are AI reason codes (no model output fits in one, D-68).
ALTER TABLE briefings
    ADD COLUMN narration        TEXT,
    ADD COLUMN narration_status VARCHAR(20) NOT NULL DEFAULT 'none',
    ADD COLUMN narration_code   VARCHAR(48),
    ADD COLUMN narration_model  VARCHAR(100);

ALTER TABLE briefings
    ADD CONSTRAINT briefings_narration_status_check
        CHECK (narration_status IN ('none', 'narrated', 'rejected', 'unavailable')),
    ADD CONSTRAINT briefings_narration_code_check
        CHECK (narration_code IS NULL OR narration_code ~ '^[a-z0-9_]{1,48}$'),
    ADD CONSTRAINT briefings_narration_length_check
        CHECK (narration IS NULL OR char_length(narration) BETWEEN 1 AND 900),
    -- Exactly one shape per status: text and model only when narrated, a code only when it fell back.
    ADD CONSTRAINT briefings_narration_shape_check
        CHECK (
            (narration_status = 'none'        AND narration IS NULL     AND narration_model IS NULL     AND narration_code IS NULL) OR
            (narration_status = 'narrated'    AND narration IS NOT NULL AND narration_model IS NOT NULL AND narration_code IS NULL) OR
            (narration_status IN ('rejected', 'unavailable') AND narration IS NULL AND narration_model IS NULL AND narration_code IS NOT NULL)
        );
