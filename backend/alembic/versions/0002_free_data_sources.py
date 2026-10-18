"""ICAO-Codes, OpenSky-Abflug-Cache und Flugzeugregister-Cache

Revision ID: 0002_free_data_sources
Revises: 0001_initial
Create Date: 2026-10-18
"""
import sqlalchemy as sa
from alembic import op

revision = "0002_free_data_sources"
down_revision = "0001_initial"
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table("airports") as batch:
        batch.add_column(sa.Column("icao", sa.String(4)))
    with op.batch_alter_table("airlines") as batch:
        batch.add_column(sa.Column("icao", sa.String(3)))
    op.create_table(
        "opensky_departure_days",
        sa.Column("airport_icao", sa.String(4), primary_key=True),
        sa.Column("day", sa.String(10), primary_key=True),
        sa.Column("callsigns", sa.JSON, nullable=False),
        sa.Column("fetched_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_table(
        "aircraft_registry",
        sa.Column("icao24", sa.String(6), primary_key=True),
        sa.Column("type_code", sa.String(8)),
        sa.Column("registration", sa.String(16)),
        sa.Column("description", sa.String(120)),
        sa.Column("fetched_at", sa.DateTime(timezone=True), nullable=False),
    )


def downgrade() -> None:
    op.drop_table("aircraft_registry")
    op.drop_table("opensky_departure_days")
    with op.batch_alter_table("airlines") as batch:
        batch.drop_column("icao")
    with op.batch_alter_table("airports") as batch:
        batch.drop_column("icao")
