-- 仅用于绑定本机端口的独立开发库。
CREATE ROLE recoow_migrator LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'recoow-local-migration';
CREATE ROLE recoow_app LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD 'recoow-local-runtime';
CREATE DATABASE recoow_dev OWNER recoow_migrator;
