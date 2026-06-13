CREATE TABLE IF NOT EXISTS lesson_grades (
    id          BIGSERIAL PRIMARY KEY,
    course_id   BIGINT NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    lesson_id   BIGINT NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,
    student_id  VARCHAR(255) NOT NULL,
    grade       INTEGER,
    feedback    TEXT,
    graded_by   VARCHAR(255) NOT NULL,
    graded_at   TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    CONSTRAINT uq_lesson_grades_lesson_student UNIQUE (lesson_id, student_id)
);

CREATE INDEX IF NOT EXISTS idx_lesson_grades_student ON lesson_grades(student_id);
CREATE INDEX IF NOT EXISTS idx_lesson_grades_course ON lesson_grades(course_id);
