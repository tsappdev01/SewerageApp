# Moving the app's database from MRDB to PropertyManagementSystem

DIP 2026-10-29: the app's tables move from MRDB into PropertyManagementSystem (PMS). Nothing of PMS's own
is changed: everything the app makes is in **schema `mr`** (tables, and views named like PMS's
`dbo.vw_MR_*` but in `mr`). The API reads `mr.vw_MR_*` (`SourceViews:Schema = mr`) and writes `mr.*`.
The only write outside `mr` stays the optional copy into `dbo.MaintainMeterReading` (`PmsTransfer:Enabled`).

No code changes: the switch is two lines in the server's `web.config`. MRDB is kept, unchanged, until the
app has run well on PMS.

## 1. Before the day (any time; the app keeps running on MRDB)

In SSMS, connected to UATWEB01, database **PropertyManagementSystem**, as a db_owner:

1. Back up PropertyManagementSystem.
2. Run the table scripts from `db/`, in this order. Each is re-runnable and only creates what is missing:
   `002 003 004 005 006 007 009 010 012 013 014 015 017 019 020 022 024 025 026`
3. Run `008_create_api_login.sql` (set `@ViewsDb = N'PropertyManagementSystem'`; the login mr_api exists
   already, so its password is not needed). This makes the user mr_api in PMS.
4. Run `028_pms_app_views.sql`. It ends with the list of `mr.vw_MR_*` views it made.
5. Do **not** run `011`, `016`, `018`, `021`, `023` or `027` in PMS: they make MRDB's wrapper views and stop
   with an error if run in PMS.

Check, as mr_api (`EXECUTE AS LOGIN = N'mr_api'; … REVERT;`):

```sql
SELECT COUNT(*) FROM mr.vw_MR_Meter;
SELECT COUNT(*) FROM mr.vw_MR_Property;
SELECT TOP 5 * FROM mr.vw_MR_Tenant;
SELECT * FROM mr.vw_MR_ReadingPeriod WHERE Status = 'OPEN';
```

The counts should match the same queries on `dbo.vw_MR_*` in MRDB. If a query says permission denied,
PMS's view reads another database: send the error, a grant is needed there.

## 2. On the day (about 15 minutes; readers should not send readings meanwhile)

1. Stop mApps: put `app_offline.htm` in the site folder on UATWEB01 (or stop its app pool).
2. In PropertyManagementSystem run `029_copy_mrdb_to_pms.sql`. Every row of its result must say **OK**.
3. Run `db/ops/fill_meter_opening_reading.sql` in PropertyManagementSystem.
4. Edit the site's `web.config` on UATWEB01 (it is never deployed; `deploy/mApps.web.config` is the template):
   - in `ConnectionStrings__MeterReading`, change `Database=MRDB` to `Database=PropertyManagementSystem`;
   - add `<environmentVariable name="SourceViews__Schema" value="mr" />`.
5. Remove `app_offline.htm` (or start the app pool). Check `/health/ready` on UATWEB01 says `ready`.
6. On a phone: download the list (properties appear), read a meter, and check the reading is in
   `PropertyManagementSystem.mr.ReadingTransaction`.

## 3. Going back (if something is wrong)

Put `Database=MRDB` back and remove the `SourceViews__Schema` line in `web.config`. Readings sent while on
PMS are only in PMS: copy them back before switching, or keep the app on PMS and fix forward.

## 4. Afterwards

- New `db/` scripts are run in PropertyManagementSystem.
- After a few weeks on PMS: back up MRDB one last time, then take it offline.
