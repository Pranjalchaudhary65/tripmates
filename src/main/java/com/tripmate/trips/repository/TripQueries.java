package com.tripmate.trips.repository;

import com.tripmate.identity.entity.VerificationTier;
import com.tripmate.trips.entity.MemberStatus;
import com.tripmate.trips.entity.TripStatus;
import com.tripmate.trips.repository.TripCards.IncomingRequest;
import com.tripmate.trips.repository.TripCards.OutgoingRequest;
import com.tripmate.trips.repository.TripCards.PersonCard;
import com.tripmate.trips.repository.TripCards.TripCard;
import com.tripmate.trips.repository.TripCards.TripPage;
import com.tripmate.trips.repository.TripCards.TripRef;
import com.tripmate.trips.repository.TripCards.ViewerRequest;
import com.tripmate.vibe.entity.BudgetBand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The list and detail reads behind the trip screens, one query per screen.
 *
 * <p>Other people's names and tiers come from {@code user_public_facts}, the
 * view identity publishes for other modules; nothing here reads
 * {@code users} itself. Every value reaches SQL as a bound parameter; the
 * only text joined into a statement is the fixed fragments below, chosen by
 * which filters are present.
 */
@Repository
public class TripQueries {

    private static final String CARD_COLUMNS = """
            t.id, t.title, t.destination_name, t.destination_latitude, t.destination_longitude,
            t.start_date, t.end_date, t.total_slots, t.filled_slots, t.budget_band, t.status,
            t.host_user_id = :viewer AS hosted_by_viewer,
            h.first_name AS host_first_name, h.id_verification_tier AS host_tier,
            m.id AS viewer_request_id, m.status AS viewer_request_status,
            CASE WHEN t.host_user_id = :viewer THEN
                (SELECT count(*) FROM trip_members p WHERE p.trip_id = t.id AND p.status = 'REQUESTED')
            END AS pending_requests""";

    private static final String CARD_FROM = """
            FROM trips t
            JOIN user_public_facts h ON h.user_id = t.host_user_id
            LEFT JOIN trip_members m ON m.trip_id = t.id AND m.user_id = :viewer""";

    private static final String SEARCH_POINT =
            "ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography";

    private final NamedParameterJdbcTemplate jdbc;

    public TripQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Open trips other people host, not yet started, whose host is still
     * active, soonest first.
     */
    public Page<TripCard> browse(UUID viewer, TripFilter filter, LocalDate today, Pageable pageable) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("viewer", viewer)
                .addValue("today", today);
        StringBuilder where = new StringBuilder("""
                WHERE t.status = 'OPEN' AND t.start_date >= :today AND h.active AND t.host_user_id <> :viewer""");
        String distance = "NULL::double precision";
        if (filter.near() != null) {
            params.addValue("latitude", filter.near().latitude())
                    .addValue("longitude", filter.near().longitude())
                    .addValue("radiusMeters", filter.near().radiusKm() * 1000);
            where.append(" AND ST_DWithin(t.destination_point, ").append(SEARCH_POINT).append(", :radiusMeters)");
            distance = "ST_Distance(t.destination_point, " + SEARCH_POINT + ") / 1000";
        }
        if (filter.from() != null) {
            params.addValue("from", filter.from());
            where.append(" AND t.start_date >= :from");
        }
        if (filter.to() != null) {
            params.addValue("to", filter.to());
            where.append(" AND t.end_date <= :to");
        }
        if (!filter.budgets().isEmpty()) {
            params.addValue("budgets", filter.budgets().stream().map(BudgetBand::name).sorted().toList());
            where.append(" AND t.budget_band IN (:budgets)");
        }
        long total = count(CARD_FROM + "\n" + where, params);
        List<TripCard> cards = page("SELECT " + CARD_COLUMNS + ", " + distance + " AS distance_km\n" + CARD_FROM
                + "\n" + where + "\nORDER BY t.start_date, t.created_at, t.id", params, pageable, TripQueries::card);
        return new PageImpl<>(cards, pageable, total);
    }

    /** Every trip the viewer hosts, those still to come first, then the most recent. */
    public Page<TripCard> hostedBy(UUID viewer, LocalDate today, Pageable pageable) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("viewer", viewer)
                .addValue("today", today);
        String where = "WHERE t.host_user_id = :viewer";
        long total = count(CARD_FROM + "\n" + where, params);
        List<TripCard> cards = page("SELECT " + CARD_COLUMNS + ", NULL::double precision AS distance_km\n"
                + CARD_FROM + "\n" + where + "\nORDER BY (t.end_date < :today), t.start_date, t.id",
                params, pageable, TripQueries::card);
        return new PageImpl<>(cards, pageable, total);
    }

    /** One trip whatever its state; whether the viewer may see it is the service's decision. */
    public Optional<TripPage> findPage(UUID tripId, UUID viewer) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("viewer", viewer)
                .addValue("trip", tripId);
        List<TripPage> rows = jdbc.query("SELECT " + CARD_COLUMNS + """
                , NULL::double precision AS distance_km,
                  t.description, t.destination_radius_km, t.created_at, t.host_user_id, h.active AS host_active
                """ + CARD_FROM + "\nWHERE t.id = :trip", params, (rs, row) -> new TripPage(card(rs, row),
                rs.getString("description"), rs.getBigDecimal("destination_radius_km"),
                instant(rs, "created_at"), rs.getObject("host_user_id", UUID.class), rs.getBoolean("host_active")));
        return rows.stream().findFirst();
    }

    /** Requests on the viewer's own trips from people still active, newest first. */
    public Page<IncomingRequest> incoming(UUID viewer, UUID tripId, MemberStatus status, Pageable pageable) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("viewer", viewer);
        StringBuilder where = new StringBuilder("WHERE t.host_user_id = :viewer AND u.active");
        if (tripId != null) {
            params.addValue("trip", tripId);
            where.append(" AND m.trip_id = :trip");
        }
        if (status != null) {
            params.addValue("status", status.name());
            where.append(" AND m.status = :status");
        }
        String from = """
                FROM trip_members m
                JOIN trips t ON t.id = m.trip_id
                JOIN user_public_facts u ON u.user_id = m.user_id
                """ + where;
        long total = count(from, params);
        List<IncomingRequest> rows = page("""
                SELECT m.id, m.status, m.requested_at, m.decided_at,
                       t.id AS trip_id, t.title, t.destination_name, t.start_date, t.end_date,
                       t.status AS trip_status, t.total_slots, t.filled_slots,
                       u.first_name, u.id_verification_tier
                """ + from + "\nORDER BY m.requested_at DESC, m.id", params, pageable,
                (rs, row) -> new IncomingRequest(rs.getObject("id", UUID.class),
                        MemberStatus.valueOf(rs.getString("status")), instant(rs, "requested_at"),
                        instant(rs, "decided_at"), tripRef(rs),
                        new PersonCard(rs.getString("first_name"),
                                VerificationTier.valueOf(rs.getString("id_verification_tier")))));
        return new PageImpl<>(rows, pageable, total);
    }

    /** The viewer's own requests, newest first. */
    public Page<OutgoingRequest> outgoing(UUID viewer, MemberStatus status, Pageable pageable) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("viewer", viewer);
        StringBuilder where = new StringBuilder("WHERE m.user_id = :viewer");
        if (status != null) {
            params.addValue("status", status.name());
            where.append(" AND m.status = :status");
        }
        String from = """
                FROM trip_members m
                JOIN trips t ON t.id = m.trip_id
                JOIN user_public_facts h ON h.user_id = t.host_user_id
                """ + where;
        long total = count(from, params);
        List<OutgoingRequest> rows = page("""
                SELECT m.id, m.status, m.requested_at, m.decided_at,
                       t.id AS trip_id, t.title, t.destination_name, t.start_date, t.end_date,
                       t.status AS trip_status, t.total_slots, t.filled_slots,
                       h.first_name, h.id_verification_tier
                """ + from + "\nORDER BY m.requested_at DESC, m.id", params, pageable,
                (rs, row) -> new OutgoingRequest(rs.getObject("id", UUID.class),
                        MemberStatus.valueOf(rs.getString("status")), instant(rs, "requested_at"),
                        instant(rs, "decided_at"), tripRef(rs),
                        new PersonCard(rs.getString("first_name"),
                                VerificationTier.valueOf(rs.getString("id_verification_tier")))));
        return new PageImpl<>(rows, pageable, total);
    }

    /** Whether an account can still take part: not suspended, banned or deleted. False for an unknown ID. */
    public boolean isActiveUser(UUID userId) {
        List<Boolean> rows = jdbc.queryForList("SELECT active FROM user_public_facts WHERE user_id = :user",
                new MapSqlParameterSource("user", userId), Boolean.class);
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.getFirst());
    }

    private long count(String from, MapSqlParameterSource params) {
        Long total = jdbc.queryForObject("SELECT count(*) " + from, params, Long.class);
        return total == null ? 0 : total;
    }

    private <T> List<T> page(String sql, MapSqlParameterSource params, Pageable pageable, RowMapper<T> mapper) {
        MapSqlParameterSource paged = new MapSqlParameterSource(params.getValues())
                .addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());
        return new ArrayList<>(jdbc.query(sql + "\nLIMIT :limit OFFSET :offset", paged, mapper));
    }

    private static TripCard card(ResultSet rs, int row) throws SQLException {
        UUID requestId = rs.getObject("viewer_request_id", UUID.class);
        long pending = rs.getLong("pending_requests");
        Integer pendingRequests = rs.wasNull() ? null : Math.toIntExact(pending);
        double distance = rs.getDouble("distance_km");
        Double distanceKm = rs.wasNull() ? null : distance;
        return new TripCard(rs.getObject("id", UUID.class), rs.getString("title"),
                rs.getString("destination_name"), rs.getDouble("destination_latitude"),
                rs.getDouble("destination_longitude"), rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class), rs.getInt("total_slots"), rs.getInt("filled_slots"),
                BudgetBand.valueOf(rs.getString("budget_band")), TripStatus.valueOf(rs.getString("status")),
                distanceKm, new PersonCard(rs.getString("host_first_name"),
                        VerificationTier.valueOf(rs.getString("host_tier"))),
                rs.getBoolean("hosted_by_viewer"),
                requestId == null ? null
                        : new ViewerRequest(requestId, MemberStatus.valueOf(rs.getString("viewer_request_status"))),
                pendingRequests);
    }

    private static TripRef tripRef(ResultSet rs) throws SQLException {
        return new TripRef(rs.getObject("trip_id", UUID.class), rs.getString("title"),
                rs.getString("destination_name"), rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class), TripStatus.valueOf(rs.getString("trip_status")),
                rs.getInt("total_slots"), rs.getInt("filled_slots"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
