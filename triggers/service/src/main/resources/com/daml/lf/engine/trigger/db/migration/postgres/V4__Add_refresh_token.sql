-- Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

-- Add refresh token to running trigger table
alter table ${table.prefix}running_triggers add column refresh_token text;
