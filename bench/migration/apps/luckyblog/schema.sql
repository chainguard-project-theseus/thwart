-- luckyBlog's tables (src/main/resources/import.sql) for H2 in MySQL mode: the same columns and
-- keys without the MySQL dump's session settings, CREATE DATABASE/USE, engine and charset clauses.
CREATE TABLE admin (
  username varchar(50) NOT NULL,
  password varchar(50) NOT NULL,
  email varchar(64) DEFAULT '#',
  github varchar(64) NOT NULL DEFAULT '#',
  twitter varchar(64) NOT NULL DEFAULT '#',
  md text,
  resume text,
  avatar varchar(80) NOT NULL DEFAULT '/img/avatar.jpg',
  PRIMARY KEY (username)
);
CREATE TABLE blog_view (
  vid int NOT NULL AUTO_INCREMENT,
  date date NOT NULL,
  title varchar(50) NOT NULL,
  article text NOT NULL,
  tags varchar(80) NOT NULL,
  md text NOT NULL,
  PRIMARY KEY (vid)
);
CREATE TABLE project (
  id int NOT NULL AUTO_INCREMENT,
  name varchar(60) NOT NULL,
  url varchar(60) NOT NULL DEFAULT '#',
  tech varchar(250) NOT NULL,
  desp text NOT NULL,
  date timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE TABLE view_tag (
  name varchar(40) NOT NULL,
  vid int NOT NULL DEFAULT 0,
  PRIMARY KEY (name, vid),
  CONSTRAINT fk_vid FOREIGN KEY (vid) REFERENCES blog_view (vid) ON DELETE CASCADE ON UPDATE CASCADE
);
