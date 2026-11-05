package repository

import (
	"database/sql"
	"fmt"
	"github.com/imehc/recoow/backend/migrations"
	"github.com/jackc/pgx/v5"
	_ "github.com/jackc/pgx/v5/stdlib"
	"net/url"
)

func Migrate(dsn, runtimeDSN string) error {
	db, err := sql.Open("pgx", dsn)
	if err != nil {
		return err
	}
	defer db.Close()
	if err := migrations.Apply(db); err != nil {
		return err
	}
	address, err := url.Parse(runtimeDSN)
	if err != nil || address.User == nil {
		return fmt.Errorf("运行账号 DSN 无效")
	}
	role := pgx.Identifier{address.User.Username()}.Sanitize()
	var namespace string
	if err := db.QueryRow("SELECT current_schema()").Scan(&namespace); err != nil {
		return err
	}
	// 运行账号只有数据访问权限，不能建表或绕过租户隔离策略。
	if _, err := db.Exec("GRANT USAGE ON SCHEMA " + pgx.Identifier{namespace}.Sanitize() + " TO " + role); err != nil {
		return err
	}
	_, err = db.Exec("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA " + pgx.Identifier{namespace}.Sanitize() + " TO " + role)
	return err
}
func CheckRuntimeRole(dsn string) error {
	db, err := sql.Open("pgx", dsn)
	if err != nil {
		return err
	}
	defer db.Close()
	var superuser, bypass bool
	if err := db.QueryRow("SELECT rolsuper,rolbypassrls FROM pg_roles WHERE rolname = current_user").Scan(&superuser, &bypass); err != nil {
		return err
	}
	if superuser || bypass {
		return fmt.Errorf("运行数据库账号不能是超级用户或具有 BYPASSRLS 权限")
	}
	var ownsTables bool
	if err := db.QueryRow(`SELECT EXISTS (SELECT 1 FROM pg_class JOIN pg_namespace ON pg_namespace.oid = pg_class.relnamespace WHERE pg_namespace.nspname = current_schema() AND pg_class.relkind IN ('r','p') AND pg_has_role(current_user, pg_class.relowner, 'MEMBER'))`).Scan(&ownsTables); err != nil {
		return err
	}
	if ownsTables {
		return fmt.Errorf("运行数据库账号不能拥有业务表；迁移账号与运行账号须分离")
	}
	return nil
}
