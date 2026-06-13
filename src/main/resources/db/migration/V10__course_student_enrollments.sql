CREATE TABLE IF NOT EXISTS course_student_enrollments (
    course_id BIGINT NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    student_id VARCHAR(255) NOT NULL,
    enrolled_at TIMESTAMP NOT NULL,
    PRIMARY KEY (course_id, student_id)
);

INSERT INTO course_student_enrollments (course_id, student_id, enrolled_at)
SELECT c.id, cs.student_id, COALESCE(c.updated_at, c.created_at, CURRENT_TIMESTAMP)
FROM courses c
JOIN course_students cs ON cs.course_id = c.id
ON CONFLICT (course_id, student_id) DO NOTHING;
