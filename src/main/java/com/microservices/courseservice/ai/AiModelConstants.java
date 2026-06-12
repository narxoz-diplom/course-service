package com.microservices.courseservice.ai;

/**
 * Platform AI identifiers. Teacher-facing APIs expose selectable LLM models only.
 * {@link #EMBEDDING_MODEL_ID} is infrastructure-only: fixed Gemini Embedding 2 for all
 * ingest/retrieval; never returned by the model catalog API or accepted as generation input.
 */
public final class AiModelConstants {

    public static final String CAPABILITY_COURSE_GENERATION = "course-generation";
    public static final String CAPABILITY_EMBEDDING = "embedding";
    public static final String DEFAULT_MODEL_ID = "gemini-3.5-flash";
    /** Fixed embedding stack — not teacher-selectable; see docs/teacher-ai-generation-operations.md */
    public static final String EMBEDDING_MODEL_ID = "gemini-embedding-2-preview";

    private AiModelConstants() {
    }
}
