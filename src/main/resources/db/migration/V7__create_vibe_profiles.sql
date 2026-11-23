-- Vibe profiles from DESIGN.md §2: vibe_profiles, deal_breakers, and the
-- saved progress of a quiz not yet finished.

CREATE TABLE vibe_profiles (
    user_id               UUID PRIMARY KEY REFERENCES users (id),
    budget_band           VARCHAR(20) NOT NULL,
    pace                  VARCHAR(20) NOT NULL,
    wake_time             VARCHAR(20) NOT NULL,
    planning_style        VARCHAR(20) NOT NULL,
    social_energy         SMALLINT NOT NULL,
    risk_appetite         SMALLINT NOT NULL,
    food_adventurousness  SMALLINT NOT NULL,
    expense_split_style   VARCHAR(20) NOT NULL,
    -- The quiz answers the dimensions were scored from, kept so a change of
    -- weights can re-score them; quiz_version says which question set.
    raw_answers           JSONB NOT NULL,
    quiz_version          SMALLINT NOT NULL,
    -- Set when the user changed a dimension by hand after the quiz. A
    -- re-scoring leaves such a row alone rather than undo the user's edit.
    adjusted_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_vibe_budget_band CHECK (budget_band IN ('SHOESTRING', 'MID', 'COMFORT', 'LUXURY')),
    CONSTRAINT ck_vibe_pace CHECK (pace IN ('PACKED', 'BALANCED', 'SLOW')),
    CONSTRAINT ck_vibe_wake_time CHECK (wake_time IN ('EARLY_BIRD', 'AVERAGE', 'NIGHT_OWL')),
    CONSTRAINT ck_vibe_planning_style
        CHECK (planning_style IN ('DETAILED_ITINERARY', 'ROUGH_PLAN', 'FULLY_SPONTANEOUS')),
    CONSTRAINT ck_vibe_social_energy CHECK (social_energy BETWEEN 1 AND 5),
    CONSTRAINT ck_vibe_risk_appetite CHECK (risk_appetite BETWEEN 1 AND 5),
    CONSTRAINT ck_vibe_food_adventurousness CHECK (food_adventurousness BETWEEN 1 AND 5),
    CONSTRAINT ck_vibe_expense_split_style
        CHECK (expense_split_style IN ('STRICT_SPLIT', 'WHOEVER_CAN', 'ONE_PAYS_ALTERNATE')),
    CONSTRAINT ck_vibe_raw_answers CHECK (jsonb_typeof(raw_answers) = 'object'),
    CONSTRAINT ck_vibe_quiz_version CHECK (quiz_version > 0)
);

CREATE TABLE deal_breakers (
    user_id            UUID PRIMARY KEY REFERENCES users (id),
    smoking            VARCHAR(10) NOT NULL,
    drinking           VARCHAR(10) NOT NULL,
    gender_preference  VARCHAR(10) NOT NULL,
    min_trust_score    SMALLINT NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_deal_breakers_smoking CHECK (smoking IN ('NO', 'OK', 'EITHER')),
    CONSTRAINT ck_deal_breakers_drinking CHECK (drinking IN ('NO', 'OK', 'EITHER')),
    CONSTRAINT ck_deal_breakers_gender_preference CHECK (gender_preference IN ('ANY', 'SAME')),
    CONSTRAINT ck_deal_breakers_min_trust_score CHECK (min_trust_score BETWEEN 0 AND 100)
);

-- Answers to a quiz still in progress, so it can be finished later or on
-- another device. Deleted when the quiz is submitted.
CREATE TABLE vibe_quiz_drafts (
    user_id       UUID PRIMARY KEY REFERENCES users (id),
    quiz_version  SMALLINT NOT NULL,
    answers       JSONB NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_vibe_drafts_answers CHECK (jsonb_typeof(answers) = 'object'),
    CONSTRAINT ck_vibe_drafts_quiz_version CHECK (quiz_version > 0)
);
