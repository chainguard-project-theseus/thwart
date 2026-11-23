-- Bench seed, run by Flyway after the app's own V1-V6: published, draft and archived posts (enough
-- for two pages of /posts and of /admin/posts), comments in every status, subscribers, messages
-- and page views. Every timestamp is fixed, before the pinned clock (2025-06-15 10:00 UTC).
insert into posts(id, title, slug, md_content, content, category_id, created_by, status, created_at) values
(1, 'Getting Started with Spring Boot 3', 'getting-started-with-spring-boot-3',
 'Spring Boot 3 needs **Java 17**.', '<p>Spring Boot 3 needs <strong>Java 17</strong>.</p>', 2, 1, 'PUBLISHED', '2025-06-01 09:00:00'),
(2, 'Testing with JUnit 5', 'testing-with-junit-5',
 'Use `@Test` and *assertions*.', '<p>Use <code>@Test</code> and <em>assertions</em>.</p>', 6, 2, 'PUBLISHED', '2025-06-02 09:00:00'),
(3, 'Microservices Patterns', 'microservices-patterns',
 'Use a *saga* or CQRS.', '<p>Use a <em>saga</em> or CQRS.</p>', 3, 1, 'PUBLISHED', '2025-06-03 09:00:00'),
(4, 'Kubernetes for Java Developers', 'kubernetes-for-java-developers',
 'Deploy with `kubectl apply`.', '<p>Deploy with <code>kubectl apply</code>.</p>', 7, 2, 'PUBLISHED', '2025-06-04 09:00:00'),
(5, 'Quarkus vs Spring Boot', 'quarkus-vs-spring-boot',
 'Both are fine & fast.', '<p>Both are fine &amp; fast.</p>', 5, 1, 'PUBLISHED', '2025-06-05 09:00:00'),
(6, 'Modular Monoliths', 'modular-monoliths',
 'Spring Modulith checks module boundaries.', '<p>Spring Modulith checks module boundaries.</p>', 8, 1, 'PUBLISHED', '2025-06-06 09:00:00'),
(7, 'Securing APIs with OAuth', 'securing-apis-with-oauth',
 'Tokens, scopes and <b>raw HTML</b> in Markdown.', '<p>Tokens, scopes and <b>raw HTML</b> in Markdown.</p>', 1, 2, 'PUBLISHED', '2025-06-07 09:00:00'),
(8, 'Draft about Gradle', 'draft-about-gradle',
 'Not ready yet.', '<p>Not ready yet.</p>', 9, 1, 'DRAFT', '2025-06-08 09:00:00'),
(9, 'Archived Testcontainers Notes', 'archived-testcontainers-notes',
 'Old notes.', '<p>Old notes.</p>', 4, 2, 'ARCHIVED', '2025-06-09 09:00:00');

insert into posts_tags(post_id, tag_id) values
(1, 2), (2, 1), (3, 2), (4, 6), (5, 1), (6, 4), (7, 3), (7, 5), (8, 8), (9, 11);

insert into comments(id, post_id, name, email, content, status, created_at) values
(1, 1, 'Alice', 'alice@example.org', 'Great intro, thanks!', 'APPROVED', '2025-06-10 08:00:00'),
(2, 1, 'Bob', 'bob@example.org', 'What about <b>Java 21</b>?', 'APPROVED', '2025-06-11 08:00:00'),
(3, 2, 'Carol', null, 'Waiting for review.', 'PENDING', '2025-06-12 08:00:00'),
(4, 3, 'Spammer', 'spam@example.org', 'Buy now', 'SPAM', '2025-06-13 08:00:00');

insert into subscribers(id, email, verified, created_at) values
(1, 'reader1@example.org', true, '2025-06-10 07:00:00'),
(2, 'reader2@example.org', false, '2025-06-11 07:00:00');

insert into messages(id, name, email, subject, content, created_at) values
(1, 'Dave', 'dave@example.org', 'Workshop', 'Do you run workshops?', '2025-06-12 06:00:00');

insert into page_views(id, path, title, session_id, created_at) values
(1, '/posts', 'blog posts', 's1', '2025-05-20 12:00:00'),
(2, '/posts', 'blog posts', 's2', '2025-06-10 12:00:00'),
(3, '/posts/getting-started-with-spring-boot-3', 'blog post details', 's1', '2025-06-11 12:00:00'),
(4, '/posts/getting-started-with-spring-boot-3', 'blog post details', 's2', '2025-06-14 12:00:00'),
(5, '/posts/getting-started-with-spring-boot-3', 'blog post details', 's3', '2025-06-15 08:00:00'),
(6, '/categories/java/posts', 'blog posts', 's3', '2025-06-15 08:30:00');
