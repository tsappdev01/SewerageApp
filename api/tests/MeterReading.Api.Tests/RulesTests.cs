using MeterReading.Api;
using MeterReading.Api.Contracts;
using MeterReading.Api.Data;
using MeterReading.Api.Domain;

namespace MeterReading.Api.Tests;

public class RulesTests
{
    private static readonly ReadingRulesOptions Rules = new();

    [Theory]
    [InlineData(null, null, AssignmentState.PENDING)]
    [InlineData("ACCEPTED", "WORKING", AssignmentState.SENT)]
    [InlineData("ACKNOWLEDGED", "WORKING", AssignmentState.SENT)]
    [InlineData("EXCEPTION", "WORKING", AssignmentState.CHECKING)]
    [InlineData("REJECTED_BY_SUPERVISOR", "WORKING", AssignmentState.READ_AGAIN)]
    [InlineData("ACCEPTED", "NOT_ACCESSIBLE", AssignmentState.REVISIT)]
    public void State_follows_latest_transaction(string? status, string? condition, AssignmentState expected) =>
        Assert.Equal(expected, AssignmentStates.From(status, condition));

    [Fact]
    public void Expected_high_is_average_times_factor() => Assert.Equal(3000m, ExpectedRange.High(1000m, "IRRIGATION", Rules));

    [Fact]
    public void Expected_high_never_below_floor() => Assert.Equal(100m, ExpectedRange.High(10m, "SEWERAGE", Rules));

    [Fact]
    public void No_average_means_no_high_warning() => Assert.Null(ExpectedRange.High(null, "SEWERAGE", Rules));

    [Fact]
    public void Source_average_wins_over_history_and_default() =>
        Assert.Equal(5m, ExpectedRange.Average(5m, 7m, "IRRIGATION", new ReadingRulesOptions { DefaultAverageIrrigation = 9m }));

    [Fact]
    public void Default_average_used_when_nothing_else() =>
        Assert.Equal(9m, ExpectedRange.Average(null, null, "IRRIGATION", new ReadingRulesOptions { DefaultAverageIrrigation = 9m }));

    [Theory]
    [InlineData("1499-W1", "1499w1", true)]
    [InlineData("1499-W1", "1499 W1", true)]
    [InlineData("1497", "149", true)]
    [InlineData("1100", "149", false)]
    public void Search_ignores_case_spaces_and_dashes(string candidate, string query, bool expected) =>
        Assert.Equal(expected, PropertySearch.Matches(candidate, PropertySearch.Normalize(query)));

    private static MeterDto Meter(long id, string number, string property, string zone, AssignmentState state, string type = "SEWERAGE") =>
        new(id, number, type, property, zone, 1, 5, 0, 100m, null, false, 300m, 900m, state, null, null);

    private static readonly MeterSet Work = new(
        [
            Meter(1, "2001-I", "1499-W1", "598", AssignmentState.PENDING, "IRRIGATION"),
            Meter(2, "2002-2", "1499-W1", "598", AssignmentState.SENT),
            Meter(3, "1497-S", "1497", "598", AssignmentState.READ_AGAIN),
            Meter(4, "1001-I", "1100", "597", AssignmentState.CHECKING, "IRRIGATION"),
        ],
        [
            new PropertyDto("1499-W1", "Building 1499-W1", "598", 1, null, null),
            new PropertyDto("1497", "Villa 1497", "598", 2, null, null),
            new PropertyDto("1100", "Villa 1100", "597", 1, null, null),
        ],
        [new ZoneDto("597", null), new ZoneDto("598", null)]);

    [Fact]
    public void Search_finds_both_buildings_for_partial_digits() =>
        Assert.Equal(["1499-W1", "1497"], ReaderService.Search(Work, "149", null, DoneFilter.ALL, null).Properties.Select(h => h.Property.Code));

    [Fact]
    public void Search_lists_meter_number_hits_from_other_buildings() =>
        Assert.Equal([4L], ReaderService.Search(Work, "1001", null, DoneFilter.ALL, null).Meters.Select(m => m.Id));

    [Fact]
    public void Search_to_read_keeps_capturable_meters_only() =>
        Assert.Equal([1L], ReaderService.Search(Work, "1499", null, DoneFilter.TO_READ, null).Properties.Single().Meters.Select(m => m.Id));

    [Fact]
    public void Search_by_type_and_zone() =>
        Assert.Empty(ReaderService.Search(Work, "", "597", DoneFilter.ALL, "SEWERAGE").Properties);

    [Fact]
    public void Summary_buckets_add_up()
    {
        var s = ReaderService.Summarize("2026-10", Work, Array.Empty<TransactionRow>());
        Assert.Equal(4, s.Meters);
        Assert.Equal(3, s.Read);
        Assert.Equal(s.Meters, s.Accepted + s.Checking + s.ReadAgain + s.Revisit + s.NotRead);
        Assert.Equal([("597", 1, 1), ("598", 3, 2)], s.Zones.Select(z => (z.ZoneCode, z.Meters, z.Read)));
        Assert.Equal(0, s.ReadByYou);
        Assert.Null(s.LastReceivedUtc);
    }
}
