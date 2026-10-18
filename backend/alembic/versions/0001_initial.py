"""Initiales Schema

Revision ID: 0001_initial
Revises:
Create Date: 2026-10-18
"""
import sqlalchemy as sa
from alembic import op

revision = "0001_initial"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "airports",
        sa.Column("iata", sa.String(3), primary_key=True),
        sa.Column("name", sa.String(120), nullable=False),
        sa.Column("city", sa.String(120), nullable=False),
        sa.Column("country", sa.String(2), nullable=False),
        sa.Column("tz", sa.String(64), nullable=False),
        sa.Column("lat", sa.Float, nullable=False),
        sa.Column("lon", sa.Float, nullable=False),
        sa.Column("mct", sa.Integer, nullable=False),
    )
    op.create_table(
        "airlines",
        sa.Column("iata", sa.String(2), primary_key=True),
        sa.Column("name", sa.String(120), nullable=False),
        sa.Column("country", sa.String(2), nullable=False),
        sa.Column("website", sa.String(255)),
        sa.Column("alliance", sa.String(40)),
    )
    op.create_table(
        "aircraft_types",
        sa.Column("id", sa.String(8), primary_key=True),
        sa.Column("manufacturer", sa.String(80), nullable=False),
        sa.Column("model", sa.String(80), nullable=False),
        sa.Column("family", sa.String(40), nullable=False),
        sa.Column("status", sa.String(16), nullable=False),
        sa.Column("default_coolness", sa.Float, nullable=False),
        sa.Column("data", sa.JSON, nullable=False),
    )
    op.create_table(
        "search_requests",
        sa.Column("id", sa.String(32), primary_key=True),
        sa.Column("request_hash", sa.String(64), nullable=False, index=True),
        sa.Column("request_json", sa.JSON, nullable=False),
        sa.Column("provider", sa.String(32), nullable=False),
        sa.Column("provider_mode", sa.String(16), nullable=False),
        sa.Column("status", sa.String(32), nullable=False),
        sa.Column("stats_json", sa.JSON, nullable=False),
        sa.Column("warnings_json", sa.JSON, nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, index=True),
    )
    op.create_table(
        "search_results",
        sa.Column("id", sa.Integer, primary_key=True, autoincrement=True),
        sa.Column("search_id", sa.String(32), sa.ForeignKey("search_requests.id", ondelete="CASCADE"),
                  nullable=False, index=True),
        sa.Column("itinerary_id", sa.String(32), nullable=False),
        sa.Column("price_total", sa.Float, nullable=False),
        sa.Column("currency", sa.String(3), nullable=False),
        sa.Column("itinerary_json", sa.JSON, nullable=False),
    )
    op.create_table(
        "release_information",
        sa.Column("tag", sa.String(40), primary_key=True),
        sa.Column("version", sa.String(40), nullable=False),
        sa.Column("published_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("prerelease", sa.Integer, nullable=False, server_default="0"),
        sa.Column("apk_url", sa.Text),
        sa.Column("sha256", sa.String(64)),
        sa.Column("fetched_at", sa.DateTime(timezone=True), nullable=False),
    )


def downgrade() -> None:
    for table in ("release_information", "search_results", "search_requests", "aircraft_types", "airlines",
                  "airports"):
        op.drop_table(table)
