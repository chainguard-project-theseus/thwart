-- Seed data for the purl_server bench (H2 in MySQL mode, loaded after classpath:schema.sql).
-- Fixed timestamps only: the app's own data.sql stamps rows with NOW(3).
--
-- Passwords: the login page SHA-1s the password in JavaScript before posting it, and the stored
-- value is '{SHA-256}' + sha256(hex(sha1(password))). admin/admin, user1/user1, user2/user2.
-- user2 has a password reset token that expires in 2286, so the set-password form succeeds.
INSERT INTO `user` (`id`, `login`, `admin`, `fullname`, `affiliation`, `email`, `password_sha`, `password_reset_token`, `comment`, `created`, `lastmodified`, `status`) VALUES
  (1, 'admin', true, 'PURL Administrator', 'My Institution', 'admin@example.org',
   '{SHA-256}b5a04226bda8fc65a475e3e76359fdec6fe75dced1924004d1e170cc47ea3f1b', null, 'main user', '2020-01-01 10:00:00.000', '2020-01-01 10:00:00.000', 'CREATED'),
  (2, 'user1', false, 'Ursula One', 'Library', 'user1@example.org',
   '{SHA-256}57ae5e0ac9e60db63f945e9d066b90fce3f8b80aec5571809b30dbbc46fe9b41', null, 'can create and modify in /test', '2020-02-01 10:00:00.000', '2020-02-01 10:00:00.000', 'CREATED'),
  (3, 'user2', false, 'Ulrich Two', 'Archive', 'user2@example.org',
   '{SHA-256}a8c7b4ddb60a81bcefcd10c4354f38ee482e39e7e87107f0c274fea79456dcd3', '9999999999999_bench-token', 'modify only', '2020-03-01 10:00:00.000', '2021-03-01 10:00:00.000', 'MODIFIED'),
  (4, 'gone', false, 'Gone User', null, null,
   '{SHA-256}b5a04226bda8fc65a475e3e76359fdec6fe75dced1924004d1e170cc47ea3f1b', null, null, '2019-01-01 10:00:00.000', '2019-06-01 10:00:00.000', 'DELETED');

INSERT INTO `domain` (`id`, `path`, `name`, `comment`, `created`, `lastmodified`, `status`) VALUES
  (1, '/test', 'Test Domain', 'Domain for <tests> & demos', '2012-07-26 11:20:32.000', '2023-09-21 12:05:06.010', 'MODIFIED'),
  (2, '/library', 'Library', '', '2015-05-05 08:00:00.000', '2015-05-05 08:00:00.000', 'CREATED'),
  (3, '/old', 'Old Domain', 'retired', '2010-01-01 09:00:00.000', '2018-01-01 09:00:00.000', 'DELETED');

INSERT INTO `domainuser` (`id`, `domain_id`, `user_id`, `can_create`, `can_modify`) VALUES
  (1, 1, 2, true, true),
  (2, 1, 3, false, true),
  (3, 2, 1, true, true),
  (4, 3, 2, true, true);

INSERT INTO `purl` (`id`, `path`, `domain_id`, `type`, `target`, `created`, `lastmodified`, `status`) VALUES
  (1, '/test/redirect', 1, 'REDIRECT_302', 'https://example.org/target', '2020-04-01 12:00:00.000', '2020-04-01 12:00:00.000', 'CREATED'),
  (2, '/test/partial', 1, 'PARTIAL_302', 'https://example.org/base/', '2020-04-02 12:00:00.000', '2021-05-02 12:30:00.000', 'MODIFIED'),
  (3, '/test/gone', 1, 'GONE_410', 'https://example.org/gone', '2020-04-03 12:00:00.000', '2022-01-03 12:00:00.000', 'DELETED'),
  (4, '/library/book1', 2, 'REDIRECT_302', 'https://example.org/books/1?a=1&b=<2>', '2016-01-01 08:00:00.000', '2016-01-01 08:00:00.000', 'CREATED');

INSERT INTO `purlhistory` (`id`, `purl_id`, `user_id`, `type`, `target`, `modified`, `status`) VALUES
  (1, 1, 2, 'REDIRECT_302', 'https://example.org/target', '2020-04-01 12:00:00.000', 'CREATED'),
  (2, 2, 2, 'REDIRECT_302', 'https://example.org/old', '2020-04-02 12:00:00.000', 'CREATED'),
  (3, 2, 3, 'PARTIAL_302', 'https://example.org/base/', '2021-05-02 12:30:00.000', 'MODIFIED'),
  (4, 3, 2, 'REDIRECT_302', 'https://example.org/gone', '2020-04-03 12:00:00.000', 'CREATED'),
  (5, 3, 1, 'GONE_410', 'https://example.org/gone', '2022-01-03 12:00:00.000', 'DELETED'),
  (6, 4, 1, 'REDIRECT_302', 'https://example.org/books/1?a=1&b=<2>', '2016-01-01 08:00:00.000', 'CREATED');
