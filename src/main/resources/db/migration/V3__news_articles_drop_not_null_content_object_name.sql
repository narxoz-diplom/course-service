-- Новая реализация хранит текст новости в колонке content.
-- В старой схеме могли оставаться колонки для внешнего "content object" (например, object storage),
-- из-за чего вставка падала на NOT NULL.
DO
$$
    BEGIN
        IF EXISTS (SELECT 1
                   FROM information_schema.tables
                   WHERE table_schema = current_schema()
                     AND table_name = 'news_articles')
            AND EXISTS (SELECT 1
                        FROM information_schema.columns
                        WHERE table_schema = current_schema()
                          AND table_name = 'news_articles'
                          AND column_name = 'content_object_name') THEN
            ALTER TABLE news_articles
                ALTER COLUMN content_object_name DROP NOT NULL;
        END IF;
    END
$$;

