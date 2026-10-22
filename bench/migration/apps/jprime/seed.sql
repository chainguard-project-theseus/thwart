-- Seed data for the jprime bench (H2 in MySQL mode; Hibernate creates the schema first).
-- Fixed dates only. The current branch is the 2099 conference: its CFP opened in 2020 and closes
-- on 2099-01-15, so the CFP form is open and ticket prices are on sale for decades yet. Rows
-- written through the app get created_date = now, but no page prints that column.

INSERT INTO Branch (label, branch_year, startDate, duration, cfpOpenDate, cfpCloseDate, soldOutPackages, soldOut, currentBranch, agendaPublished) VALUES
  ('YEAR_2099', 2099, '2099-05-27 09:00:00', 172800000000000, '2020-01-01 00:00:00', '2099-01-15 23:59:00', '["PLATINUM"]', false, true, true),
  ('YEAR_2020', 2020, '2020-05-28 09:00:00', 172800000000000, '2019-11-01 00:00:00', '2020-02-15 23:59:00', '[]', true, false, false);

INSERT INTO TicketPrice (id, price, ticketType, branch, validFrom, validUntil) VALUES
  (1, 150.00, 'EARLY_BIRD', 'YEAR_2099', '2020-01-01 00:00:00', '2099-01-01 00:00:00'),
  (2, 200.00, 'REGULAR', 'YEAR_2099', '2020-01-01 00:00:00', '2099-05-26 00:00:00'),
  (3, 75.50, 'STUDENT', 'YEAR_2099', '2020-01-01 00:00:00', '2099-05-26 00:00:00'),
  (4, 100.00, 'EARLY_BIRD', 'YEAR_2020', '2019-11-01 00:00:00', '2020-02-01 00:00:00'),
  (5, 140.00, 'REGULAR', 'YEAR_2020', '2019-11-01 00:00:00', '2020-05-27 00:00:00'),
  (6, 50.00, 'STUDENT', 'YEAR_2020', '2019-11-01 00:00:00', '2020-05-27 00:00:00');
ALTER TABLE TicketPrice ALTER COLUMN id RESTART WITH 100;

-- Users: a plain user, speakers (password "secret" for the plain user, ada and grace), sponsors.
INSERT INTO User (id, DTYPE, firstName, lastName, email, password, phone, bio, headline, twitter, bsky, sponsorPackage, companyName, companyWebsite, description, active) VALUES
  (1, 'User', 'Plain', 'User', 'plain@example.org', '$2a$10$oGbr3hY656gD1vNgOEl7BOvM7Ee.0R84opmzac7750yQvHxerBt1m', '+359 1', null, null, null, null, null, null, null, null, null),
  (2, 'Speaker', 'Ada', 'Lovelace', 'ada@example.org', '$2a$10$oGbr3hY656gD1vNgOEl7BOvM7Ee.0R84opmzac7750yQvHxerBt1m', '+359 2', 'Ada writes <b>analytical</b> engines & more.', 'Countess of Computing', '@ada', 'ada.bsky.social', null, null, null, null, null),
  (3, 'Speaker', 'Grace', 'Hopper', 'grace@example.org', '$2a$10$oGbr3hY656gD1vNgOEl7BOvM7Ee.0R84opmzac7750yQvHxerBt1m', '+359 3', 'Grace found the first bug.', 'Rear Admiral', 'https://x.com/grace', null, null, null, null, null, null),
  (4, 'Speaker', 'Alan', 'Turing', 'alan@example.org', null, null, 'Alan asks whether machines can think.', 'Mathematician', null, null, null, null, null, null, null),
  (5, 'Speaker', 'Edsger', 'Dijkstra', 'edsger@example.org', null, null, 'Goto considered harmful.', 'Professor', null, null, null, null, null, null, null),
  (6, 'Sponsor', 'Pat', 'Platinum', 'pat@platinum.example', null, null, null, null, null, null, 'PLATINUM', 'Platinum Corp', 'https://platinum.example', 'The biggest sponsor', true),
  (7, 'Sponsor', 'Gil', 'Gold', 'gil@gold.example', null, null, null, null, null, null, 'GOLD', 'Gold & Sons', 'https://gold.example', 'A golden sponsor', true),
  (8, 'Sponsor', 'Sid', 'Silver', 'sid@silver.example', null, null, null, null, null, null, 'SILVER', 'Silver Ltd', 'https://silver.example', 'Inactive sponsor', false);
ALTER TABLE User ALTER COLUMN id RESTART WITH 100;

INSERT INTO Partner (id, companyName, companyWebsite, description, active, partnerPackage) VALUES
  (1, 'Media Partner', 'https://media.example', 'Writes about us', true, 'MEDIA'),
  (2, 'Supporting Org', 'https://support.example', 'Supports us', true, 'SUPPORTERS'),
  (3, 'Event Partner', 'https://event.example', 'Helps with events', true, 'OTHER');
ALTER TABLE Partner ALTER COLUMN id RESTART WITH 100;

INSERT INTO Tag (id, name) VALUES (1, 'news'), (2, 'agenda');
ALTER TABLE Tag ALTER COLUMN id RESTART WITH 100;

INSERT INTO Article (id, title, description, text, published, author, created_date) VALUES
  (1, 'Call for papers is open', 'Submit your talk', '<p>The CFP for <b>2099</b> is open.</p>', true, 1, '2098-01-01 10:00:00'),
  (2, 'Tickets on sale', 'Get yours', '<p>Early bird tickets are available.</p>', true, 1, '2098-02-01 10:00:00'),
  (3, 'The agenda', 'Two days of talks', '<p>See the agenda.</p>', true, 1, '2098-03-01 10:00:00'),
  (4, 'Draft post', 'Not yet', '<p>Unpublished.</p>', false, 1, '2098-04-01 10:00:00');
ALTER TABLE Article ALTER COLUMN id RESTART WITH 100;
INSERT INTO tags_articles (article_pk, tag_pk) VALUES (1, 1), (2, 1), (3, 2), (4, 1);

INSERT INTO Submission (id, title, description, status, level, type, speaker, coSpeaker, branch, featured) VALUES
  (1, 'Analytical Engines in Java', 'How to build an engine.', 'CONFIRMED', 'ADVANCED', 'CONFERENCE_SESSION', 2, null, 'YEAR_2099', true),
  (2, 'Debugging with Moths', 'Finding bugs, literally.', 'CONFIRMED', 'BEGINNER', 'WORKSHOP', 3, 2, 'YEAR_2099', false),
  (3, 'Thinking Machines', 'Can machines think?', 'ACCEPTED', 'INTERMEDIATE', 'CONFERENCE_SESSION', 4, null, 'YEAR_2099', false),
  (4, 'Structured Programming', 'No more goto.', 'SUBMITTED', 'INTERMEDIATE', 'DEEP_DIVE', 5, null, 'YEAR_2099', false),
  (5, 'Shortest Paths', 'A graph talk.', 'REJECTED', 'BEGINNER', 'CONFERENCE_SESSION', 5, null, 'YEAR_2099', false),
  (6, 'Old Talk', 'From 2020.', 'CONFIRMED', 'BEGINNER', 'CONFERENCE_SESSION', 4, null, 'YEAR_2020', false);
ALTER TABLE Submission ALTER COLUMN id RESTART WITH 100;

INSERT INTO VenueHall (id, name, description) VALUES
  (1, 'Hall A', 'The big hall'), (2, 'Hall B', 'The small hall'), (3, 'Workshops', 'Lab room');
ALTER TABLE VenueHall ALTER COLUMN id RESTART WITH 100;

INSERT INTO Session (id, title, start_time, end_time, submission, hall) VALUES
  (1, null, '2099-05-27 10:00:00', '2099-05-27 10:50:00', 1, 1),
  (2, null, '2099-05-27 11:00:00', '2099-05-27 12:50:00', 2, 3),
  (3, 'Break', '2099-05-27 10:50:00', '2099-05-27 11:00:00', null, null),
  (4, 'TBA', '2099-05-28 10:00:00', '2099-05-28 10:50:00', null, 2);
ALTER TABLE Session ALTER COLUMN id RESTART WITH 100;

INSERT INTO Registrant (id, isCompany, isStudent, name, address, vatNumber, mol, email, eik, epayInvoiceNumber, realInvoiceNumber, proformaInvoiceNumber, paymentType, branch, created_date) VALUES
  (1, true, false, 'Acme Ltd', '1 Main St, Sofia', 'BG123456789', 'Wile E. Coyote', 'billing@acme.example', '123456789', 0, 0, 1001, 1, 'YEAR_2099', '2098-06-01 12:00:00'),
  (2, false, true, 'Stu Dent', 'Dorm 5', null, null, 'stu@example.org', null, 0, 0, 1002, 1, 'YEAR_2099', '2098-06-02 12:00:00');
ALTER TABLE Registrant ALTER COLUMN id RESTART WITH 100;

INSERT INTO Visitor (id, registrant, name, email, company, ticket, isPresent, isRegistered, status) VALUES
  (1, 1, 'Road Runner', 'rr@acme.example', 'Acme Ltd', 'TICKET-0001', true, true, 'PAYED'),
  (2, 1, 'Wile Coyote', 'wile@acme.example', 'Acme Ltd', null, false, false, 'REQUESTING'),
  (3, 2, 'Stu Dent', 'stu@example.org', null, null, false, false, 'SPONSORED');
ALTER TABLE Visitor ALTER COLUMN id RESTART WITH 100;

INSERT INTO VisitorJPro (id, name, email, company, isPresent, status) VALUES
  (1, 'Jay Pro', 'jay@jpro.example', 'JPro Inc', true, 'PAYED'),
  (2, 'Kay Pro', 'kay@jpro.example', null, false, 'REQUESTING');
ALTER TABLE VisitorJPro ALTER COLUMN id RESTART WITH 100;

-- A password reset token for the plain user; the link uses the token "benchresettoken", the table
-- stores its SHA-512. Created in 2099, so it stays valid.
INSERT INTO ResetPasswordToken (id, userId, tokenId, used, created_date) VALUES
  (1, 1, '2982fce739e9812a6e84bf72675e75c81bfb0a51ae4b79763e1255719dfcd030dbb42c089ef322b2ac59a170e77bc486564a523cdc424c8a506ddb3fb0936c78', false, '2099-01-01 00:00:00');
ALTER TABLE ResetPasswordToken ALTER COLUMN id RESTART WITH 100;
