-- Старая схема news_articles без поля content (JPA NewsArticle.content -> колонка content)
DO
$$
    BEGIN
        IF EXISTS (SELECT 1
                   FROM information_schema.tables
                   WHERE table_schema = current_schema()
                     AND table_name = 'news_articles')
            AND NOT EXISTS (SELECT 1
                            FROM information_schema.columns
                            WHERE table_schema = current_schema()
                              AND table_name = 'news_articles'
                              AND column_name = 'content') THEN
            ALTER TABLE news_articles ADD COLUMN content TEXT;
            UPDATE news_articles
            SET content = COALESCE(NULLIF(TRIM(short_description), ''), '')
            WHERE content IS NULL;
            ALTER TABLE news_articles ALTER COLUMN content SET NOT NULL;
        END IF;
    END
$$;
