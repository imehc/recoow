-- 配对登录不要求绑定密码身份，旧密码会话继续保留原来的身份约束。
ALTER TABLE sessions ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE sessions ADD COLUMN auth_method TEXT NOT NULL DEFAULT 'password'
    CHECK (auth_method IN ('password', 'pairing'));
ALTER TABLE sessions ADD CONSTRAINT sessions_password_identity
    CHECK (auth_method = 'pairing' OR user_id IS NOT NULL);
