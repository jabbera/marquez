/* SPDX-License-Identifier: Apache-2.0 */

CREATE TABLE dataset_symlinks (
  symlink_uuid      UUID,
  name              VARCHAR(255) NOT NULL,
  namespace_uuid    UUID REFERENCES namespaces(uuid),
  type              VARCHAR(64),
  is_primary        BOOLEAN DEFAULT FALSE,
  created_at        TIMESTAMP NOT NULL,
  updated_at        TIMESTAMP NOT NULL,
  UNIQUE (namespace_uuid, name)
);

INSERT INTO dataset_symlinks (symlink_uuid, name, namespace_uuid, is_primary, created_at, updated_at)
SELECT d.uuid, d.name, d.namespace_uuid, TRUE, d.created_at, d.updated_at FROM datasets d;

ALTER TABLE datasets ADD COLUMN symlink_uuid uuid;
ALTER TABLE datasets DROP CONSTRAINT datasets_namespace_uuid_name_key;
ALTER TABLE datasets ADD UNIQUE (namespace_uuid, symlink_uuid);
UPDATE datasets SET symlink_uuid = uuid;
ALTER TABLE datasets DROP COLUMN name;
