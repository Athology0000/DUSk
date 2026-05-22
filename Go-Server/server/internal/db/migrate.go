package db

import (
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

// duplicateObjectCodes are the Postgres SQLSTATEs raised when a migration tries
// to create something that already exists — i.e. the migration's objects are
// already present in the database.
var duplicateObjectCodes = map[string]bool{
	"42P07": true, // duplicate_table
	"42P06": true, // duplicate_schema
	"42710": true, // duplicate_object (constraint, index, type, trigger, ...)
	"42701": true, // duplicate_column
	"42723": true, // duplicate_function
}

func isDuplicateObjectErr(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && duplicateObjectCodes[pgErr.Code]
}

// RunMigrations applies every *.sql file in dir that has not yet been recorded
// in the schema_migrations table, in filename order, each inside its own
// transaction. It is idempotent and safe to call on every boot.
//
// If the tracking table starts empty, the database may already carry a schema
// that was created before auto-migration existed. In that case a migration
// whose objects already exist is *adopted* (recorded as applied) rather than
// failing the boot — so a pre-existing manually-migrated database is brought
// under tracking without a crash. A missing directory is a no-op.
func RunMigrations(ctx context.Context, pool *pgxpool.Pool, dir string) error {
	if _, err := pool.Exec(ctx, `
		CREATE TABLE IF NOT EXISTS schema_migrations (
			filename   TEXT PRIMARY KEY,
			applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
		)`); err != nil {
		return fmt.Errorf("create schema_migrations: %w", err)
	}

	var trackedCount int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM schema_migrations`).Scan(&trackedCount); err != nil {
		return fmt.Errorf("count schema_migrations: %w", err)
	}
	adoptExisting := trackedCount == 0

	entries, err := os.ReadDir(dir)
	if err != nil {
		if os.IsNotExist(err) {
			log.Printf("[migrate] directory %q not found; skipping migrations", dir)
			return nil
		}
		return fmt.Errorf("read migrations dir %q: %w", dir, err)
	}

	files := make([]string, 0, len(entries))
	for _, e := range entries {
		if !e.IsDir() && strings.HasSuffix(strings.ToLower(e.Name()), ".sql") {
			files = append(files, e.Name())
		}
	}
	sort.Strings(files)

	applied, adopted := 0, 0
	for _, name := range files {
		var exists bool
		if err := pool.QueryRow(ctx,
			`SELECT EXISTS(SELECT 1 FROM schema_migrations WHERE filename = $1)`, name,
		).Scan(&exists); err != nil {
			return fmt.Errorf("check migration %q: %w", name, err)
		}
		if exists {
			continue
		}

		sqlBytes, err := os.ReadFile(filepath.Join(dir, name))
		if err != nil {
			return fmt.Errorf("read migration %q: %w", name, err)
		}

		tx, err := pool.Begin(ctx)
		if err != nil {
			return fmt.Errorf("begin migration %q: %w", name, err)
		}

		if _, err := tx.Exec(ctx, string(sqlBytes)); err != nil {
			_ = tx.Rollback(ctx)

			if adoptExisting && isDuplicateObjectErr(err) {
				if _, recErr := pool.Exec(ctx,
					`INSERT INTO schema_migrations (filename) VALUES ($1)
					 ON CONFLICT (filename) DO NOTHING`, name,
				); recErr != nil {
					return fmt.Errorf("adopt migration %q: %w", name, recErr)
				}
				log.Printf("[migrate] %s — objects already present, marked as applied", name)
				adopted++
				continue
			}

			return fmt.Errorf("apply migration %q: %w", name, err)
		}

		if _, err := tx.Exec(ctx,
			`INSERT INTO schema_migrations (filename) VALUES ($1)`, name,
		); err != nil {
			_ = tx.Rollback(ctx)
			return fmt.Errorf("record migration %q: %w", name, err)
		}
		if err := tx.Commit(ctx); err != nil {
			return fmt.Errorf("commit migration %q: %w", name, err)
		}

		log.Printf("[migrate] applied %s", name)
		applied++
	}

	log.Printf("[migrate] done — %d applied, %d adopted, %d migration file(s) total", applied, adopted, len(files))
	return nil
}
