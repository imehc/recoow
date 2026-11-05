package repository

import (
	"context"
	"database/sql"
	"fmt"
	"github.com/imehc/recoow/backend/internal/repository/sqlgen"
	"strings"
)

// PostgreSQL 使用序号参数；业务层 SQL 不拼接请求值。
func bindSQL(query string) string {
	var result strings.Builder
	index := 1
	quoted := false
	for _, char := range query {
		if char == '\'' {
			quoted = !quoted
		}
		if char == '?' && !quoted {
			fmt.Fprintf(&result, "$%d", index)
			index++
		} else {
			result.WriteRune(char)
		}
	}
	return result.String()
}

type storeDB struct{ *sql.DB }
type storeTx struct{ *sql.Tx }

func (db *storeDB) QueryRow(query string, args ...any) *sql.Row {
	return db.DB.QueryRow(bindSQL(query), args...)
}
func (db *storeDB) Query(query string, args ...any) (*sql.Rows, error) {
	return db.DB.Query(bindSQL(query), args...)
}
func (db *storeDB) Exec(query string, args ...any) (sql.Result, error) {
	return db.DB.Exec(bindSQL(query), args...)
}
func (db *storeDB) QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row {
	return db.DB.QueryRowContext(ctx, bindSQL(query), args...)
}
func (db *storeDB) QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error) {
	return db.DB.QueryContext(ctx, bindSQL(query), args...)
}
func (db *storeDB) ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error) {
	return db.DB.ExecContext(ctx, bindSQL(query), args...)
}
func (db *storeDB) BeginTx(ctx context.Context, opts *sql.TxOptions) (*storeTx, error) {
	tx, err := db.DB.BeginTx(ctx, opts)
	return &storeTx{tx}, err
}
func (db *storeDB) Begin() (*storeTx, error) { return db.BeginTx(context.Background(), nil) }
func (tx *storeTx) QueryRow(query string, args ...any) *sql.Row {
	return tx.Tx.QueryRow(bindSQL(query), args...)
}
func (tx *storeTx) Query(query string, args ...any) (*sql.Rows, error) {
	return tx.Tx.Query(bindSQL(query), args...)
}
func (tx *storeTx) Exec(query string, args ...any) (sql.Result, error) {
	return tx.Tx.Exec(bindSQL(query), args...)
}
func (tx *storeTx) QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row {
	return tx.Tx.QueryRowContext(ctx, bindSQL(query), args...)
}
func (tx *storeTx) QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error) {
	return tx.Tx.QueryContext(ctx, bindSQL(query), args...)
}
func (tx *storeTx) ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error) {
	return tx.Tx.ExecContext(ctx, bindSQL(query), args...)
}

func (storage *postgresStore) beginAccount(accountID string) (*storeTx, error) {
	tx, err := storage.db.Begin()
	if err != nil {
		return nil, err
	}
	if err := sqlgen.New(tx.Tx).SetAccountContext(storage.ctx, accountID); err != nil {
		_ = tx.Rollback()
		return nil, err
	}
	return tx, nil
}
