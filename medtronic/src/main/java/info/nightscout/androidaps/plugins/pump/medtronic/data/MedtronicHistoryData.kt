package info.nightscout.androidaps.plugins.pump.medtronic.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.data.DetailedBolusInfo
import info.nightscout.androidaps.interfaces.ActivePlugin
import info.nightscout.androidaps.interfaces.PumpSync
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.LTag
import info.nightscout.androidaps.plugins.pump.common.defs.PumpType
import info.nightscout.androidaps.plugins.pump.common.utils.DateTimeUtil
import info.nightscout.androidaps.plugins.pump.common.utils.StringUtil
import info.nightscout.androidaps.plugins.pump.medtronic.comm.history.pump.MedtronicPumpHistoryDecoder
import info.nightscout.androidaps.plugins.pump.medtronic.comm.history.pump.PumpHistoryEntry
import info.nightscout.androidaps.plugins.pump.medtronic.comm.history.pump.PumpHistoryEntryType
import info.nightscout.androidaps.plugins.pump.medtronic.comm.history.pump.PumpHistoryResult
import info.nightscout.androidaps.plugins.pump.medtronic.data.dto.*
import info.nightscout.androidaps.plugins.pump.medtronic.defs.MedtronicDeviceType
import info.nightscout.androidaps.plugins.pump.medtronic.defs.PumpBolusType
import info.nightscout.androidaps.plugins.pump.medtronic.driver.MedtronicPumpStatus
import info.nightscout.androidaps.plugins.pump.medtronic.util.MedtronicConst
import info.nightscout.androidaps.plugins.pump.medtronic.util.MedtronicUtil
import info.nightscout.androidaps.utils.sharedPreferences.SP
import org.apache.commons.lang3.StringUtils
import org.joda.time.LocalDateTime
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Created by andy on 10/12/18.
 */
// TODO: After release we need to refactor how data is retrieved from pump, each entry in history needs to be marked, and sorting
//  needs to happen according those markings, not on time stamp (since AAPS can change time anytime it drifts away). This
//  needs to include not returning any records if TZ goes into -x area. To fully support this AAPS would need to take note of
//  all times that time changed (TZ, DST, etc.). Data needs to be returned in batches (time_changed batches, so that we can
//  handle it. It would help to assign sort_ids to items (from oldest (1) to newest (x)
//
@Suppress("DEPRECATION")
@Singleton
class MedtronicHistoryData @Inject constructor(
    val injector: HasAndroidInjector,
    val aapsLogger: AAPSLogger,
    val sp: SP,
    val activePlugin: ActivePlugin,
    val medtronicUtil: MedtronicUtil,
    val medtronicPumpHistoryDecoder: MedtronicPumpHistoryDecoder,
    val medtronicPumpStatus: MedtronicPumpStatus,
    val pumpSync: PumpSync,
    val pumpSyncStorage: info.nightscout.androidaps.plugins.pump.common.sync.PumpSyncStorage
) {

    val allHistory: MutableList<PumpHistoryEntry> = mutableListOf()
    private var allPumpIds: MutableSet<Long> = mutableSetOf()
    private var newHistory: MutableList<PumpHistoryEntry> = mutableListOf()
    private var isInit = false

    private var pumpTime: ClockDTO? = null
    private var lastIdUsed: Long = 0
    private var gson: Gson = GsonBuilder().excludeFieldsWithoutExposeAnnotation().create()
    private var gsonCore: Gson = GsonBuilder().create()

    /**
     * Add New History entries
     *
     * @param result PumpHistoryResult instance
     */
    fun addNewHistory(result: PumpHistoryResult) {
        val validEntries: List<PumpHistoryEntry> = result.validEntries
        val newEntries: MutableList<PumpHistoryEntry> = mutableListOf()
        for (validEntry in validEntries) {
            if (!allPumpIds.contains(validEntry.pumpId)) {
                newEntries.add(validEntry)
            } else {
                val entryByPumpId = getEntryByPumpId(validEntry.pumpId)

                if (entryByPumpId != null && entryByPumpId.hasBolusChanged(validEntry)) {
                    newEntries.add(validEntry)
                    allHistory.remove(entryByPumpId)
                    allPumpIds.remove(validEntry.pumpId)
                }
            }
        }
        newHistory = newEntries
        showLogs("List of history (before filtering): [" + newHistory.size + "]", gson.toJson(newHistory))
    }

    private fun getEntryByPumpId(pumpId: Long): PumpHistoryEntry? {
        val findFirst = this.allHistory.stream()
            .filter { f -> f.pumpId == pumpId }
            .findFirst()

        return if (findFirst.isPresent()) findFirst.get() else null
    }

    private fun showLogs(header: String?, data: String) {
        if (header != null) {
            aapsLogger.debug(LTag.PUMP, header)
        }
        if (StringUtils.isNotBlank(data)) {
            for (token in StringUtil.splitString(data, 3500)) {
                aapsLogger.debug(LTag.PUMP, token)
            }
        } else {
            aapsLogger.debug(LTag.PUMP, "No data.")
        }
    }

    fun filterNewEntries() {
        val newHistory2: MutableList<PumpHistoryEntry> = mutableListOf()
        var tbrs: MutableList<PumpHistoryEntry> = mutableListOf()
        val bolusEstimates: MutableList<PumpHistoryEntry> = mutableListOf()
        val atechDate = DateTimeUtil.toATechDate(GregorianCalendar())

        //aapsLogger.debug(LTag.PUMP, "Filter new entries: Before {}", newHistory);
        if (!isCollectionEmpty(newHistory)) {
            for (pumpHistoryEntry in newHistory) {
                if (!allPumpIds.contains(pumpHistoryEntry.pumpId)) {
                    val type = pumpHistoryEntry.entryType
                    if (type === PumpHistoryEntryType.TempBasalRate || type === PumpHistoryEntryType.TempBasalDuration) {
                        tbrs.add(pumpHistoryEntry)
                    } else if (type === PumpHistoryEntryType.BolusWizard || type === PumpHistoryEntryType.BolusWizard512) {
                        bolusEstimates.add(pumpHistoryEntry)
                        newHistory2.add(pumpHistoryEntry)
                    } else {
                        if (type === PumpHistoryEntryType.EndResultTotals) {
                            if (!DateTimeUtil.isSameDay(atechDate, pumpHistoryEntry.atechDateTime)) {
                                newHistory2.add(pumpHistoryEntry)
                            }
                        } else {
                            newHistory2.add(pumpHistoryEntry)
                        }
                    }
                }
            }
            tbrs = preProcessTBRs(tbrs)
            if (bolusEstimates.size > 0) {
                extendBolusRecords(bolusEstimates, newHistory2)
            }
            newHistory2.addAll(tbrs)

            val newHistory3: MutableList<PumpHistoryEntry> = mutableListOf()

            for (pumpHistoryEntry in newHistory2) {
                if (!allPumpIds.contains(pumpHistoryEntry.pumpId)) {
                    newHistory3.add(pumpHistoryEntry)
                }
            }

            newHistory = newHistory3
            sort(newHistory)
        }
        aapsLogger.debug(LTag.PUMP, "New History entries found: " + newHistory.size)
        showLogs("List of history (after filtering): [" + newHistory.size + "]", gson.toJson(newHistory))
    }

    private fun extendBolusRecords(bolusEstimates: MutableList<PumpHistoryEntry>, newHistory2: MutableList<PumpHistoryEntry>) {
        val boluses: MutableList<PumpHistoryEntry> = getFilteredItems(newHistory2, PumpHistoryEntryType.Bolus)
        for (bolusEstimate in bolusEstimates) {
            for (bolus in boluses) {
                if (bolusEstimate.atechDateTime == bolus.atechDateTime) {
                    bolus.addDecodedData("Estimate", bolusEstimate.decodedData["Object"]!!)
                }
            }
        }
    }

    fun finalizeNewHistoryRecords() {
        if (newHistory.isEmpty()) return
        var pheLast = newHistory[0]

        // find last entry
        for (pumpHistoryEntry in newHistory) {
            if (pumpHistoryEntry.atechDateTime != 0L && pumpHistoryEntry.isAfter(pheLast.atechDateTime)) {
                pheLast = pumpHistoryEntry
            }
        }

        // add new entries
        newHistory.reverse()
        for (pumpHistoryEntry in newHistory) {
            if (!allPumpIds.contains(pumpHistoryEntry.pumpId)) {
                lastIdUsed++
                pumpHistoryEntry.id = lastIdUsed
                allHistory.add(pumpHistoryEntry)
                allPumpIds.add(pumpHistoryEntry.pumpId)
            }
        }

        sp.putLong(MedtronicConst.Statistics.LastPumpHistoryEntry, pheLast.atechDateTime)
        var dt: LocalDateTime? = null
        try {
            dt = DateTimeUtil.toLocalDateTime(pheLast.atechDateTime)
        } catch (ex: Exception) {
            aapsLogger.error("Problem decoding date from last record: $pheLast")
        }
        if (dt != null) {
            dt = dt.minusDays(1) // we keep 24 hours
            val dtRemove = DateTimeUtil.toATechDate(dt)
            val removeList: MutableList<PumpHistoryEntry?> = ArrayList()
            for (pumpHistoryEntry in allHistory) {
                if (!pumpHistoryEntry.isAfter(dtRemove)) {
                    removeList.add(pumpHistoryEntry)
                    allPumpIds.remove(pumpHistoryEntry.pumpId)
                }
            }
            allHistory.removeAll(removeList)
            this.sort(allHistory)
            aapsLogger.debug(LTag.PUMP, String.format(Locale.ENGLISH, "All History records [afterFilterCount=%d, removedItemsCount=%d, newItemsCount=%d]",
                allHistory.size, removeList.size, newHistory.size))
        } else {
            aapsLogger.error("Since we couldn't determine date, we don't clean full history. This is just workaround.")
        }
        newHistory.clear()
    }

    fun hasRelevantConfigurationChanged(): Boolean {
        return getStateFromFilteredList( //
            setOf(PumpHistoryEntryType.ChangeBasalPattern,  //
                PumpHistoryEntryType.ClearSettings,  //
                PumpHistoryEntryType.SaveSettings,  //
                PumpHistoryEntryType.ChangeMaxBolus,  //
                PumpHistoryEntryType.ChangeMaxBasal,  //
                PumpHistoryEntryType.ChangeTempBasalType))
    }

    private fun isCollectionEmpty(col: List<*>?): Boolean {
        return col == null || col.isEmpty()
    }

    private fun isCollectionNotEmpty(col: List<*>?): Boolean {
        return col != null && !col.isEmpty()
    }

    fun isPumpSuspended(): Boolean {
        val items = getDataForPumpSuspends()
        showLogs("isPumpSuspended: ", gson.toJson(items))
        return if (isCollectionNotEmpty(items)) {
            val pumpHistoryEntryType = items[0].entryType
            val isSuspended = !(pumpHistoryEntryType === PumpHistoryEntryType.TempBasalCombined || //
                pumpHistoryEntryType === PumpHistoryEntryType.BasalProfileStart || //
                pumpHistoryEntryType === PumpHistoryEntryType.Bolus || //
                pumpHistoryEntryType === PumpHistoryEntryType.ResumePump || //
                pumpHistoryEntryType === PumpHistoryEntryType.BatteryChange || //
                pumpHistoryEntryType === PumpHistoryEntryType.Prime)
            aapsLogger.debug(LTag.PUMP, String.format(Locale.ENGLISH, "isPumpSuspended. Last entry type=%s, isSuspended=%b", pumpHistoryEntryType, isSuspended))
            isSuspended
        } else false
    }

    private fun getDataForPumpSuspends(): MutableList<PumpHistoryEntry> {
        val newAndAll: MutableList<PumpHistoryEntry> = mutableListOf()
        if (isCollectionNotEmpty(allHistory)) {
            newAndAll.addAll(allHistory)
        }
        if (isCollectionNotEmpty(newHistory)) {
            for (pumpHistoryEntry in newHistory) {
                if (!newAndAll.contains(pumpHistoryEntry)) {
                    newAndAll.add(pumpHistoryEntry)
                }
            }
        }
        if (newAndAll.isEmpty()) return newAndAll
        this.sort(newAndAll)
        var newAndAll2: MutableList<PumpHistoryEntry> = getFilteredItems(newAndAll,  //
            setOf(PumpHistoryEntryType.Bolus,  //
                PumpHistoryEntryType.TempBasalCombined,  //
                PumpHistoryEntryType.Prime,  //
                PumpHistoryEntryType.SuspendPump,  //
                PumpHistoryEntryType.ResumePump,  //
                PumpHistoryEntryType.Rewind,  //
                PumpHistoryEntryType.NoDeliveryAlarm,  //
                PumpHistoryEntryType.BatteryChange,  //
                PumpHistoryEntryType.BasalProfileStart))
        newAndAll2 = filterPumpSuspend(newAndAll2, 10)
        return newAndAll2
    }

    private fun filterPumpSuspend(newAndAll: MutableList<PumpHistoryEntry>, filterCount: Int): MutableList<PumpHistoryEntry> {
        if (newAndAll.size <= filterCount) {
            return newAndAll
        }
        val newAndAllOut: MutableList<PumpHistoryEntry> = ArrayList()
        for (i in 0 until filterCount) {
            newAndAllOut.add(newAndAll[i])
        }
        return newAndAllOut
    }

    /**
     * Process History Data: Boluses(Treatments), TDD, TBRs, Suspend-Resume (or other pump stops: battery, prime)
     */
    fun processNewHistoryData() {

        // Prime (for reseting autosense)
        val primeRecords: MutableList<PumpHistoryEntry> = getFilteredItems(PumpHistoryEntryType.Prime)
        aapsLogger.debug(LTag.PUMP, String.format(Locale.ENGLISH, "ProcessHistoryData: Prime [count=%d, items=%s]", primeRecords.size, gson.toJson(primeRecords)))
        if (isCollectionNotEmpty(primeRecords)) {
            try {
                processPrime(primeRecords)
            } catch (ex: Exception) {
                aapsLogger.error(LTag.PUMP, "ProcessHistoryData: Error processing Prime entries: " + ex.message, ex)
                throw ex
            }
        }

        // Rewind (for marking insulin change)
        val rewindRecords: MutableList<PumpHistoryEntry> = getFilteredItems(PumpHistoryEntryType.Rewind)
        aapsLogger.debug(LTag.PUMP, String.format(Locale.ENGLISH, "ProcessHistoryData: Rewind [count=%d, items=%s]", rewindRecords.size, gson.toJson(rewindRecords)))
        if (isCollectionNotEmpty(rewindRecords)) {
            try {
                processRewind(rewindRecords)
            } catch (ex: Exception) {
                aapsLogger.error(LTag.PUMP, "ProcessHistoryData: Error processing Rewind entries: " + ex.message, ex)
                throw ex
            }
        }

        // TDD
        val tdds: MutableList<PumpHistoryEntry> = getFilteredItems(setOf(PumpHistoryEntryType.EndResultTotals, getTDDType()))
        aapsLogger.debug(LTag.PUMP, String.format(Locale.ENGLISH, "ProcessHistoryData: TDD [count=%d, items=%s]", tdds.size, gson.toJson(tdds)))
        if (tdds.isNotEmpty()) {
            try {
                processTDDs(tdds)
            } catch (ex: Exception) {
                aapsLogger.error("ProcessHistoryData: Error processing TDD entries: " + ex.message, ex)
                throw ex
            }
        }
        pumpTime = medtronicUtil.pumpTime

        // Bolus
        val treatments = getFilteredItems(PumpHistoryEntryType.Bolus)
        aapsLogger.debug(LTag.PUMP, String.format(Locale.ENGLISH, "ProcessHistoryData: Bolus [count=%d, items=%s]", treatments.size, gson.toJson(treatments)))
        if (treatments.isNotEmpty()) {
            try {
                processBolusEntries(treatments)
            } catch (ex: Exception) {
                aapsLogger.error(LTag.PUMP, "ProcessHistoryData: Error processing Bolus entries: " + ex.message, ex)
                throw ex
            }
        }

        // TBR
        val tbrs: MutableList<PumpHistoryEntry> = getFilteredItems(PumpHistoryEntryType.TempBasalCombined)
        aapsLogger.debug(LTag.PUMP, String.format(Locale.ENGLISH, "ProcessHistoryData: TBRs Processed [count=%d, items=%s]", tbrs.size, gson.toJson(tbrs)))
        if (tbrs.isNotEmpty()) {
            try {
                processTBREntries(tbrs) // TODO not implemented yet
            } catch (ex: Exception) {
                aapsLogger.error(LTag.PUMP, "ProcessHistoryData: Error processing TBR entries: " + ex.message, ex)
                throw ex
            }
        }

        // 'Delivery Suspend'
        val suspends: MutableList<TempBasalProcessDTO>
        suspends = try {
            getSuspendRecords()
        } catch (ex: Exception) {
            aapsLogger.error("ProcessHistoryData: Error getting Suspend entries: " + ex.message, ex)
            throw ex
        }
        aapsLogger.debug(LTag.PUMP, String.format(Locale.ENGLISH, "ProcessHistoryData: 'Delivery Suspend' Processed [count=%d, items=%s]", suspends.size,
            gson.toJson(suspends)))
        if (suspends.isNotEmpty()) {
            try {
                processSuspends(suspends)  // TODO not tested yet
            } catch (ex: Exception) {
                aapsLogger.error(LTag.PUMP, "ProcessHistoryData: Error processing Suspends entries: " + ex.message, ex)
                throw ex
            }
        }
    }

    private fun processPrime(primeRecords: List<PumpHistoryEntry>) {
        val maxAllowedTimeInPast = DateTimeUtil.getATDWithAddedMinutes(GregorianCalendar(), -30)
        var lastPrimeRecordTime = 0L
        var lastPrimeRecord: PumpHistoryEntry? = null
        for (primeRecord in primeRecords) {
            val fixedAmount = primeRecord.getDecodedDataEntry("FixedAmount")
            if (fixedAmount != null && fixedAmount as Float == 0.0f) {
                // non-fixed primes are used to prime the tubing
                // fixed primes are used to prime the cannula
                // so skip the prime entry if it was not a fixed prime
                continue
            }
            if (primeRecord.atechDateTime > maxAllowedTimeInPast) {
                if (lastPrimeRecordTime != 0L && lastPrimeRecordTime < primeRecord.atechDateTime) {
                    lastPrimeRecordTime = primeRecord.atechDateTime
                    lastPrimeRecord = primeRecord
                }
            }
        }
        if (lastPrimeRecord != null) {
            uploadCareportalEventIfFoundInHistory(lastPrimeRecord,
                MedtronicConst.Statistics.LastPrime,
                DetailedBolusInfo.EventType.CANNULA_CHANGE)
        }
    }

    private fun processRewind(rewindRecords: List<PumpHistoryEntry>) {
        val maxAllowedTimeInPast = DateTimeUtil.getATDWithAddedMinutes(GregorianCalendar(), -30)
        var lastRewindRecordTime = 0L
        var lastRewindRecord: PumpHistoryEntry? = null
        for (rewindRecord in rewindRecords) {
            if (rewindRecord.atechDateTime > maxAllowedTimeInPast) {
                if (lastRewindRecordTime < rewindRecord.atechDateTime) {
                    lastRewindRecordTime = rewindRecord.atechDateTime
                    lastRewindRecord = rewindRecord
                }
            }
        }
        if (lastRewindRecord != null) {
            uploadCareportalEventIfFoundInHistory(lastRewindRecord,
                MedtronicConst.Statistics.LastRewind,
                DetailedBolusInfo.EventType.INSULIN_CHANGE)
        }
    }

    private fun uploadCareportalEventIfFoundInHistory(historyRecord: PumpHistoryEntry, eventSP: String, eventType: DetailedBolusInfo.EventType) {
        val lastPrimeFromAAPS = sp.getLong(eventSP, 0L)
        if (historyRecord.atechDateTime != lastPrimeFromAAPS) {
            val result = pumpSync.insertTherapyEventIfNewWithTimestamp(
                DateTimeUtil.toMillisFromATD(historyRecord.atechDateTime),
                eventType, null,
                historyRecord.pumpId,
                medtronicPumpStatus.pumpType,
                medtronicPumpStatus.serialNumber)

            aapsLogger.debug(LTag.PUMP, String.format(Locale.ROOT, "insertTherapyEventIfNewWithTimestamp [date=%d, eventType=%s, pumpId=%d, pumpSerial=%s] - Result: %b",
                historyRecord.atechDateTime, eventType, historyRecord.pumpId,
                medtronicPumpStatus.serialNumber, result))

            sp.putLong(eventSP, historyRecord.atechDateTime)
        }
    }

    private fun processTDDs(tddsIn: MutableList<PumpHistoryEntry>) {
        val tdds = filterTDDs(tddsIn)

        aapsLogger.debug(LTag.PUMP, String.format(Locale.ENGLISH, logPrefix + "TDDs found: %d.\n%s",
            tdds.size, gson.toJson(tdds)))

        for (tdd in tdds) {
            val totalsDTO = tdd.decodedData["Object"] as DailyTotalsDTO

            pumpSync.createOrUpdateTotalDailyDose(
                DateTimeUtil.toMillisFromATD(tdd.atechDateTime),
                totalsDTO.insulinBolus,
                totalsDTO.insulinBasal,
                totalsDTO.insulinTotal,
                tdd.pumpId,
                medtronicPumpStatus.pumpType,
                medtronicPumpStatus.serialNumber
            )
        }
    }

    private enum class ProcessHistoryRecord(val description: String) {
        Bolus("Bolus"),
        TBR("TBR"),
        Suspend("Suspend");
    }

    private fun processBolusEntries(entryList: MutableList<PumpHistoryEntry>) {

        val boluses = pumpSyncStorage.getBoluses()

        for (bolus in entryList) {

            val bolusDTO = bolus.decodedData["Object"] as BolusDTO
            var type: DetailedBolusInfo.BolusType = DetailedBolusInfo.BolusType.NORMAL
            var multiwave = false

            if (bolusDTO.bolusType == PumpBolusType.Extended) {
                addExtendedBolus(bolus, bolusDTO, multiwave)
                continue
            } else if (bolusDTO.bolusType == PumpBolusType.Multiwave) {
                multiwave = true
                aapsLogger.debug(LTag.PUMP, String.format(Locale.ENGLISH, "Multiwave bolus from pump, extended bolus and normal bolus will be added."))
                addExtendedBolus(bolus, bolusDTO, multiwave)
            }

            val deliveredAmount: Double = if (multiwave) bolusDTO.immediateAmount!! else bolusDTO.deliveredAmount

            var temporaryId: Long? = null

            if (!multiwave) {
                val entryWithTempId = findDbEntry(bolus, boluses)

                aapsLogger.debug(LTag.PUMP, String.format("DD: entryWithTempId=%s", gson.toJson(entryWithTempId)))

                if (entryWithTempId != null) {
                    temporaryId = entryWithTempId.temporaryId
                    pumpSyncStorage.removeBolusWithTemporaryId(temporaryId)
                    boluses.remove(entryWithTempId)
                    type = entryWithTempId.bolusData!!.bolusType
                }
            }

            if (temporaryId != null) {
                val result = pumpSync.syncBolusWithTempId(
                    tryToGetByLocalTime(bolus.atechDateTime),
                    deliveredAmount,
                    temporaryId,
                    type,
                    bolus.pumpId,
                    medtronicPumpStatus.pumpType,
                    medtronicPumpStatus.serialNumber)

                aapsLogger.debug(LTag.PUMP, String.format(Locale.ENGLISH, "syncBolusWithTempId [date=%d, temporaryId=%d, pumpId=%d, insulin=%.2f, pumpSerial=%s] - Result: %b",
                    bolus.atechDateTime, temporaryId, bolus.pumpId, deliveredAmount,
                    medtronicPumpStatus.serialNumber, result))
            } else {
                val result = pumpSync.syncBolusWithPumpId(
                    tryToGetByLocalTime(bolus.atechDateTime),
                    deliveredAmount,
                    type,
                    bolus.pumpId,
                    medtronicPumpStatus.pumpType,
                    medtronicPumpStatus.serialNumber)

                aapsLogger.debug(LTag.PUMP, String.format(Locale.ENGLISH, "syncBolusWithPumpId [date=%d, pumpId=%d, insulin=%.2f, pumpSerial=%s] - Result: %b",
                    bolus.atechDateTime, bolus.pumpId, deliveredAmount,
                    medtronicPumpStatus.serialNumber, result))
            }

            addCarbs(bolus)
        }
    }

    private fun addExtendedBolus(bolus: PumpHistoryEntry, bolusDTO: BolusDTO, isMultiwave: Boolean) {
        val durationMs: Long = bolusDTO.duration * 60L * 1000L

        val result = pumpSync.syncExtendedBolusWithPumpId(
            tryToGetByLocalTime(bolus.atechDateTime),
            bolusDTO.deliveredAmount,
            durationMs,
            false,
            bolus.pumpId,
            medtronicPumpStatus.pumpType,
            medtronicPumpStatus.serialNumber)

        aapsLogger.debug(LTag.PUMP, String.format(Locale.ENGLISH, "syncExtendedBolusWithPumpId [date=%d, amount=%.2f, duration=%d, pumpId=%d, pumpSerial=%s, multiwave=%b] - Result: %b",
            bolus.atechDateTime, bolusDTO.deliveredAmount, bolusDTO.duration, bolus.pumpId,
            medtronicPumpStatus.serialNumber, isMultiwave, result))
    }

    private fun addCarbs(bolus: PumpHistoryEntry) {
        if (bolus.containsDecodedData("Estimate")) {
            val bolusWizard = bolus.decodedData["Estimate"] as BolusWizardDTO

            pumpSyncStorage.addCarbs(info.nightscout.androidaps.plugins.pump.common.sync.PumpDbEntryCarbs(
                tryToGetByLocalTime(bolus.atechDateTime),
                bolusWizard.carbs.toDouble(),
                medtronicPumpStatus.pumpType,
                medtronicPumpStatus.serialNumber,
                bolus.pumpId
            ))
        }
    }

    private fun processTBREntries(entryList: MutableList<PumpHistoryEntry>) {
        Collections.reverse(entryList)
        val tbr = entryList[0].getDecodedDataEntry("Object") as TempBasalPair
        var readOldItem = false
        if (tbr.isCancelTBR) {
            val oneMoreEntryFromHistory = getOneMoreEntryFromHistory(PumpHistoryEntryType.TempBasalCombined)
            if (oneMoreEntryFromHistory != null) {
                entryList.add(0, oneMoreEntryFromHistory)
                readOldItem = true
            } else {
                entryList.removeAt(0)
            }
        }

        val tbrRecords = pumpSyncStorage.getTBRs()
        aapsLogger.debug(LTag.PUMP, String.format(Locale.ENGLISH, ProcessHistoryRecord.TBR.description + " List (before filter): %s, FromDb=%s", gson.toJson(entryList),
            gson.toJson(tbrRecords)))
        var processDTO: TempBasalProcessDTO? = null
        val processList: MutableList<TempBasalProcessDTO> = mutableListOf()
        for (treatment in entryList) {
            val tbr2 = treatment.getDecodedDataEntry("Object") as TempBasalPair
            if (tbr2.isCancelTBR) {
                if (processDTO != null) {
                    processDTO.itemTwo = treatment
                    processDTO.cancelPresent = true
                    if (readOldItem) {
                        processDTO.processOperation = TempBasalProcessDTO.Operation.Edit
                        readOldItem = false
                    }
                } else {
                    aapsLogger.warn(LTag.PUMP, "processDTO was null - shouldn't happen, ignoring item. ItemTwo=$treatment")
                }
            } else {
                if (processDTO != null) {
                    processList.add(processDTO)
                }
                processDTO = TempBasalProcessDTO(
                    itemOne = treatment,
                    processOperation = TempBasalProcessDTO.Operation.Add)
            }
        }
        if (processDTO != null) {
            processList.add(processDTO)
        }
        if (processList.isNotEmpty()) {
            for (tempBasalProcessDTO in processList) {

                val entryWithTempId = findDbEntry(tempBasalProcessDTO.itemOne, tbrRecords)

                aapsLogger.debug(LTag.PUMP, "DD: entryWithTempId: " + (if (entryWithTempId == null) "null" else entryWithTempId.toString()))

                val tbrEntry = tempBasalProcessDTO.itemOne.getDecodedDataEntry("Object") as TempBasalPair

                aapsLogger.debug(LTag.PUMP, String.format("DD: tbrEntry=%s, tempBasalProcessDTO=%s", gson.toJson(tbrEntry), gson.toJson(tempBasalProcessDTO)))

                if (entryWithTempId != null) {

                    aapsLogger.debug(LTag.PUMP, String.format("DD: tempIdEntry=%s, tbrEntry=%s, tempBasalProcessDTO=%s, pumpType=%s, serial=%s",
                        gson.toJson(entryWithTempId), gson.toJson(tbrEntry), gson.toJson(tempBasalProcessDTO), medtronicPumpStatus.pumpType, medtronicPumpStatus.serialNumber))

                    val result = pumpSync.syncTemporaryBasalWithTempId(
                        tryToGetByLocalTime(tempBasalProcessDTO.atechDateTime),
                        tbrEntry.insulinRate,
                        tempBasalProcessDTO.duration * 60L * 1000L,
                        !tbrEntry.isPercent,
                        entryWithTempId.temporaryId,
                        PumpSync.TemporaryBasalType.NORMAL,
                        tempBasalProcessDTO.pumpId,
                        medtronicPumpStatus.pumpType,
                        medtronicPumpStatus.serialNumber)

                    aapsLogger.debug(LTag.PUMP, String.format(Locale.ENGLISH, "syncTemporaryBasalWithTempId [date=%d, temporaryId=%d, pumpId=%d, rate=%.2f %s, duration=%d, pumpSerial=%s] - Result: %b",
                        tempBasalProcessDTO.atechDateTime, entryWithTempId.temporaryId, tempBasalProcessDTO.pumpId,
                        tbrEntry.insulinRate, (if (tbrEntry.isPercent) "%" else "U"), tempBasalProcessDTO.duration,
                        medtronicPumpStatus.serialNumber, result))

                    pumpSyncStorage.removeTemporaryBasalWithTemporaryId(entryWithTempId.temporaryId)
                    tbrRecords.remove(entryWithTempId)

                    entryWithTempId.pumpId = tempBasalProcessDTO.pumpId
                    entryWithTempId.date = tryToGetByLocalTime(tempBasalProcessDTO.atechDateTime)

                    if (isTBRActive(entryWithTempId)) {
                        medtronicPumpStatus.runningTBR = entryWithTempId
                    }

                } else {
                    val result = pumpSync.syncTemporaryBasalWithPumpId(
                        tryToGetByLocalTime(tempBasalProcessDTO.atechDateTime),
                        tbrEntry.insulinRate,
                        tempBasalProcessDTO.duration * 60L * 1000L,
                        !tbrEntry.isPercent,
                        PumpSync.TemporaryBasalType.NORMAL,
                        tempBasalProcessDTO.pumpId,
                        medtronicPumpStatus.pumpType,
                        medtronicPumpStatus.serialNumber)

                    aapsLogger.debug(LTag.PUMP, String.format(Locale.ENGLISH, "syncTemporaryBasalWithPumpId [date=%d, pumpId=%d, rate=%.2f %s, duration=%d, pumpSerial=%s] - Result: %b",
                        tempBasalProcessDTO.atechDateTime, tempBasalProcessDTO.pumpId,
                        tbrEntry.insulinRate, (if (tbrEntry.isPercent) "%" else "U"), tempBasalProcessDTO.duration,
                        medtronicPumpStatus.serialNumber, result))

                    if (medtronicPumpStatus.runningTBR != null) {
                        if (!isTBRActive(medtronicPumpStatus.runningTBR!!)) {
                            medtronicPumpStatus.runningTBR = null
                        }
                    }

                    if (isTBRActive(tryToGetByLocalTime(tempBasalProcessDTO.atechDateTime), tempBasalProcessDTO.duration)) {
                        if (medtronicPumpStatus.runningTBR == null) {
                            medtronicPumpStatus.runningTBR = info.nightscout.androidaps.plugins.pump.common.sync.PumpDbEntry(0L,
                                tryToGetByLocalTime(tempBasalProcessDTO.atechDateTime),
                                medtronicPumpStatus.pumpType,
                                medtronicPumpStatus.serialNumber,
                                null,
                                info.nightscout.androidaps.plugins.pump.common.sync.PumpDbEntryTBR(tbrEntry.insulinRate, !tbrEntry.isPercent, tempBasalProcessDTO.duration, PumpSync.TemporaryBasalType.NORMAL),
                                tempBasalProcessDTO.pumpId)
                        }
                    }
                }
            } // for
        } // collection
    }

    fun isTBRActive(dbEntry: info.nightscout.androidaps.plugins.pump.common.sync.PumpDbEntry): Boolean {
        return isTBRActive(dbEntry.date, dbEntry.tbrData!!.durationInMinutes)
    }

    fun isTBRActive(startTimestamp: Long, durationMin: Int): Boolean {
        val endDate = startTimestamp + (durationMin * 60 * 1000)

        return (endDate > System.currentTimeMillis())
    }

    /**
     * findDbEntry - finds Db entries in database, while theoretically this should have same dateTime they
     * don't. Entry on pump is few seconds before treatment in AAPS, and on manual boluses on pump there
     * is no treatment at all. For now we look fro tratment that was from 0s - 1m59s within pump entry.
     *
     * @param treatment          Pump Entry
     * @param entriesFromHistory entries from history
     * @return DbObject from AAPS (if found)
     */

    /**
     * Looks at all boluses that have temporaryId and find one that is correct for us (if such entry exists)
     */
    private fun findDbEntry(treatment: PumpHistoryEntry, temporaryEntries: MutableList<info.nightscout.androidaps.plugins.pump.common.sync.PumpDbEntry>): info.nightscout.androidaps.plugins.pump.common.sync.PumpDbEntry? {

        if (temporaryEntries.isEmpty()) {
            return null
        }

        var proposedTime = DateTimeUtil.toMillisFromATD(treatment.atechDateTime)

        // pumpTime should never be null, but it can theoretically happen if reading of time from pump fails
        this.pumpTime?.let { proposedTime += (it.timeDifference * 1000) }

        val proposedTimeDiff: LongArray = longArrayOf(proposedTime - (2 * 60 * 1000), proposedTime + (2L * 60L * 1000L))
        val tempEntriesList: MutableList<info.nightscout.androidaps.plugins.pump.common.sync.PumpDbEntry> = mutableListOf()

        for (temporaryEntry in temporaryEntries) {
            if (temporaryEntry.date > proposedTimeDiff[0] && temporaryEntry.date < proposedTimeDiff[1]) {
                tempEntriesList.add(temporaryEntry)
            }
        }

        if (tempEntriesList.isEmpty()) {
            return null
        } else if (tempEntriesList.size == 1) {
            return tempEntriesList[0]
        }

        var min = 0
        while (min < 2) {
            var sec = 0
            while (sec <= 50) {
                if (min == 1 && sec == 50) {
                    sec = 59
                }
                val diff = sec * 1000
                val outList: MutableList<info.nightscout.androidaps.plugins.pump.common.sync.PumpDbEntry> = mutableListOf()
                for (treatment1 in tempEntriesList) {
                    if (treatment1.date > proposedTime - diff && treatment1.date < proposedTime + diff) {
                        outList.add(treatment1)
                    }
                }
                if (outList.size == 1) {
                    if (doubleBolusDebug) aapsLogger.debug(LTag.PUMP, String.format(Locale.ENGLISH, "DoubleBolusDebug: findDbEntry Treatment={}, FromDb={}. Type=EntrySelected, AtTimeMin={}, AtTimeSec={}", treatment, outList[0], min, sec))
                    return outList[0]
                }
                if (min == 0 && sec == 10 && outList.size > 1) {
                    aapsLogger.error(String.format(Locale.ENGLISH, "Too many entries (with too small diff): (timeDiff=[min=%d,sec=%d],count=%d,list=%s)",
                        min, sec, outList.size, gson.toJson(outList)))
                    if (doubleBolusDebug) aapsLogger.debug(LTag.PUMP, String.format(Locale.ENGLISH, "DoubleBolusDebug: findDbEntry Error - Too many entries (with too small diff): (timeDiff=[min=%d,sec=%d],count=%d,list=%s)",
                        min, sec, outList.size, gson.toJson(outList)))
                }
                sec += 10
            }
            min += 1
        }
        return null
    }

    private fun processSuspends(tempBasalProcessList: List<TempBasalProcessDTO>) {
        for (tempBasalProcess in tempBasalProcessList) {

            val result = pumpSync.syncTemporaryBasalWithPumpId(
                tryToGetByLocalTime(tempBasalProcess.itemOne.atechDateTime),
                0.0,
                tempBasalProcess.duration * 60 * 1000L,
                true,
                PumpSync.TemporaryBasalType.PUMP_SUSPEND,
                tempBasalProcess.itemOne.pumpId,
                medtronicPumpStatus.pumpType,
                medtronicPumpStatus.serialNumber)

            aapsLogger.debug(LTag.PUMP, String.format(Locale.ENGLISH, "processSuspends::syncTemporaryBasalWithPumpId [date=%d, rate=%.2f, duration=%d, pumpId=%d, pumpSerial=%s] - Result: %b",
                tempBasalProcess.itemOne.atechDateTime, 0.0, tempBasalProcess.duration, tempBasalProcess.itemOne.pumpId,
                medtronicPumpStatus.serialNumber, result))

        }
    }

    // suspend/resume
    // no_delivery/prime & rewind/prime
    private fun getSuspendRecords(): MutableList<TempBasalProcessDTO> {
        val outList: MutableList<TempBasalProcessDTO> = mutableListOf()

        // suspend/resume
        outList.addAll(getSuspendResumeRecordsList())
        // no_delivery/prime & rewind/prime
        outList.addAll(getNoDeliveryRewindPrimeRecordsList())
        return outList
    }

    private fun getSuspendResumeRecordsList(): List<TempBasalProcessDTO> {
        val filteredItems = getFilteredItems(newHistory,  //
            setOf(PumpHistoryEntryType.SuspendPump, PumpHistoryEntryType.ResumePump))
        val outList: MutableList<TempBasalProcessDTO> = mutableListOf()
        if (filteredItems.size > 0) {
            val filtered2Items: MutableList<PumpHistoryEntry> = mutableListOf()
            if (filteredItems.size % 2 == 0 && filteredItems[0].entryType === PumpHistoryEntryType.ResumePump) {
                // full resume suspends (S R S R)
                filtered2Items.addAll(filteredItems)
            } else if (filteredItems.size % 2 == 0 && filteredItems[0].entryType === PumpHistoryEntryType.SuspendPump) {
                // not full suspends, need to retrive one more record and discard first one (R S R S) -> ([S] R S R [xS])
                filteredItems.removeAt(0)
                val oneMoreEntryFromHistory = getOneMoreEntryFromHistory(PumpHistoryEntryType.SuspendPump)
                if (oneMoreEntryFromHistory != null) {
                    filteredItems.add(oneMoreEntryFromHistory)
                } else {
                    filteredItems.removeAt(filteredItems.size - 1) // remove last (unpaired R)
                }
                filtered2Items.addAll(filteredItems)
            } else {
                if (filteredItems[0].entryType === PumpHistoryEntryType.ResumePump) {
                    // get one more from history (R S R) -> ([S] R S R)
                    val oneMoreEntryFromHistory = getOneMoreEntryFromHistory(PumpHistoryEntryType.SuspendPump)
                    if (oneMoreEntryFromHistory != null) {
                        filteredItems.add(oneMoreEntryFromHistory)
                    } else {
                        filteredItems.removeAt(filteredItems.size - 1) // remove last (unpaired R)
                    }
                    filtered2Items.addAll(filteredItems)
                } else {
                    // remove last and have paired items
                    filteredItems.removeAt(0)
                    filtered2Items.addAll(filteredItems)
                }
            }
            if (filtered2Items.size > 0) {
                sort(filtered2Items)
                Collections.reverse(filtered2Items)
                var i = 0
                while (i < filtered2Items.size) {
                    outList.add(TempBasalProcessDTO(
                        itemOne = filtered2Items[i],
                        itemTwo = filtered2Items[i + 1],
                        processOperation = TempBasalProcessDTO.Operation.Add))

                    i += 2
                }
            }
        }
        return outList
    }

    private fun getNoDeliveryRewindPrimeRecordsList(): List<TempBasalProcessDTO> {
        val primeItems: MutableList<PumpHistoryEntry> = getFilteredItems(newHistory,  //
            setOf(PumpHistoryEntryType.Prime))
        val outList: MutableList<TempBasalProcessDTO> = ArrayList()
        if (primeItems.size == 0) return outList
        val filteredItems: MutableList<PumpHistoryEntry> = getFilteredItems(newHistory,  //
            setOf(PumpHistoryEntryType.Prime,
                PumpHistoryEntryType.Rewind,
                PumpHistoryEntryType.NoDeliveryAlarm,
                PumpHistoryEntryType.Bolus,
                PumpHistoryEntryType.TempBasalCombined)
        )
        val tempData: MutableList<PumpHistoryEntry> = mutableListOf()
        var startedItems = false
        var finishedItems = false
        for (filteredItem in filteredItems) {
            if (filteredItem.entryType === PumpHistoryEntryType.Prime) {
                startedItems = true
            }
            if (startedItems) {
                if (filteredItem.entryType === PumpHistoryEntryType.Bolus ||
                    filteredItem.entryType === PumpHistoryEntryType.TempBasalCombined) {
                    finishedItems = true
                    break
                }
                tempData.add(filteredItem)
            }
        }
        if (!finishedItems) {
            val filteredItemsOld: MutableList<PumpHistoryEntry> = getFilteredItems(allHistory,  //
                setOf(PumpHistoryEntryType.Rewind,
                    PumpHistoryEntryType.NoDeliveryAlarm,
                    PumpHistoryEntryType.Bolus,
                    PumpHistoryEntryType.TempBasalCombined)
            )
            for (filteredItem in filteredItemsOld) {
                if (filteredItem.entryType === PumpHistoryEntryType.Bolus ||
                    filteredItem.entryType === PumpHistoryEntryType.TempBasalCombined) {
                    finishedItems = true
                    break
                }
                tempData.add(filteredItem)
            }
        }
        if (!finishedItems) {
            showLogs("NoDeliveryRewindPrimeRecords: Not finished Items: ", gson.toJson(tempData))
            return outList
        }
        showLogs("NoDeliveryRewindPrimeRecords: Records to evaluate: ", gson.toJson(tempData))
        var items: MutableList<PumpHistoryEntry> = getFilteredItems(tempData, PumpHistoryEntryType.Prime)
        var itemTwo = items[0]
        items = getFilteredItems(tempData, PumpHistoryEntryType.NoDeliveryAlarm)
        if (items.size > 0) {
            outList.add(TempBasalProcessDTO(
                itemOne = items[items.size - 1],
                itemTwo = itemTwo,
                processOperation = TempBasalProcessDTO.Operation.Add))
            return outList
        }
        items = getFilteredItems(tempData, PumpHistoryEntryType.Rewind)
        if (items.size > 0) {
            outList.add(TempBasalProcessDTO(
                itemOne = items[0],
                processOperation = TempBasalProcessDTO.Operation.Add))
            return outList
        }
        return outList
    }

    private fun getOneMoreEntryFromHistory(entryType: PumpHistoryEntryType): PumpHistoryEntry? {
        val filteredItems: List<PumpHistoryEntry?> = getFilteredItems(allHistory, entryType)
        return if (filteredItems.size == 0) null else filteredItems[0]
    }

    private fun filterTDDs(tdds: MutableList<PumpHistoryEntry>): MutableList<PumpHistoryEntry> {
        val tddsOut: MutableList<PumpHistoryEntry> = mutableListOf()
        for (tdd in tdds) {
            if (tdd.entryType !== PumpHistoryEntryType.EndResultTotals) {
                tddsOut.add(tdd)
            }
        }
        return if (tddsOut.size == 0) tdds else tddsOut
    }

    private fun tryToGetByLocalTime(atechDateTime: Long): Long {
        return DateTimeUtil.toMillisFromATD(atechDateTime)
    }

    private fun getTDDType(): PumpHistoryEntryType {
        return if (!medtronicUtil.isModelSet) {
            PumpHistoryEntryType.EndResultTotals
        } else when (medtronicUtil.medtronicPumpModel) {
            MedtronicDeviceType.Medtronic_515,
            MedtronicDeviceType.Medtronic_715     -> PumpHistoryEntryType.DailyTotals515
            MedtronicDeviceType.Medtronic_522,
            MedtronicDeviceType.Medtronic_722     -> PumpHistoryEntryType.DailyTotals522
            MedtronicDeviceType.Medtronic_523_Revel,
            MedtronicDeviceType.Medtronic_723_Revel,
            MedtronicDeviceType.Medtronic_554_Veo,
            MedtronicDeviceType.Medtronic_754_Veo -> PumpHistoryEntryType.DailyTotals523

            else                                  -> {
                PumpHistoryEntryType.EndResultTotals
            }
        }
    }

    fun hasBasalProfileChanged(): Boolean {
        val filteredItems: List<PumpHistoryEntry?> = getFilteredItems(PumpHistoryEntryType.ChangeBasalProfile_NewProfile)
        aapsLogger.debug(LTag.PUMP, "hasBasalProfileChanged. Items: " + gson.toJson(filteredItems))
        return filteredItems.size > 0
    }

    fun processLastBasalProfileChange(pumpType: PumpType, mdtPumpStatus: MedtronicPumpStatus) {
        val filteredItems: List<PumpHistoryEntry> = getFilteredItems(PumpHistoryEntryType.ChangeBasalProfile_NewProfile)
        aapsLogger.debug(LTag.PUMP, "processLastBasalProfileChange. Items: $filteredItems")
        var newProfile: PumpHistoryEntry? = null
        var lastDate: Long? = null
        if (filteredItems.size == 1) {
            newProfile = filteredItems[0]
        } else if (filteredItems.size > 1) {
            for (filteredItem in filteredItems) {
                if (lastDate == null || lastDate < filteredItem.atechDateTime) {
                    newProfile = filteredItem
                    lastDate = newProfile.atechDateTime
                }
            }
        }
        if (newProfile != null) {
            aapsLogger.debug(LTag.PUMP, "processLastBasalProfileChange. item found, setting new basalProfileLocally: $newProfile")
            val basalProfile = newProfile.decodedData["Object"] as BasalProfile
            mdtPumpStatus.basalsByHour = basalProfile.getProfilesByHour(pumpType)
        }
    }

    fun hasPumpTimeChanged(): Boolean {
        return getStateFromFilteredList(setOf(PumpHistoryEntryType.NewTimeSet,  //
            PumpHistoryEntryType.ChangeTime))
    }

    fun setIsInInit(init: Boolean) {
        isInit = init
    }

    // HELPER METHODS
    private fun sort(list: MutableList<PumpHistoryEntry>) {
        // if (list != null && !list.isEmpty()) {
        //     Collections.sort(list, PumpHistoryEntry.Comparator())
        // }
        list.sortWith(PumpHistoryEntry.Comparator())
    }

    private fun preProcessTBRs(TBRs_Input: MutableList<PumpHistoryEntry>): MutableList<PumpHistoryEntry> {
        val TBRs: MutableList<PumpHistoryEntry> = mutableListOf()
        val map: MutableMap<String?, PumpHistoryEntry?> = HashMap()
        for (pumpHistoryEntry in TBRs_Input) {
            if (map.containsKey(pumpHistoryEntry.DT)) {
                medtronicPumpHistoryDecoder.decodeTempBasal(map[pumpHistoryEntry.DT]!!, pumpHistoryEntry)
                pumpHistoryEntry.setEntryType(medtronicUtil.medtronicPumpModel, PumpHistoryEntryType.TempBasalCombined)
                TBRs.add(pumpHistoryEntry)
                map.remove(pumpHistoryEntry.DT)
            } else {
                map[pumpHistoryEntry.DT] = pumpHistoryEntry
            }
        }
        return TBRs
    }

    private fun getFilteredItems(entryTypes: Set<PumpHistoryEntryType>?): MutableList<PumpHistoryEntry> {
        return getFilteredItems(newHistory, entryTypes)
    }

    private fun getFilteredItems(entryType: PumpHistoryEntryType): MutableList<PumpHistoryEntry> {
        return getFilteredItems(newHistory, setOf(entryType))
    }

    private fun getStateFromFilteredList(entryTypes: Set<PumpHistoryEntryType>?): Boolean {
        return if (isInit) {
            false
        } else {
            val filteredItems: List<PumpHistoryEntry?> = getFilteredItems(entryTypes)
            aapsLogger.debug(LTag.PUMP, "Items: $filteredItems")
            filteredItems.size > 0
        }
    }

    private fun getFilteredItems(inList: MutableList<PumpHistoryEntry>?, entryType: PumpHistoryEntryType): MutableList<PumpHistoryEntry> {
        return getFilteredItems(inList, setOf(entryType))
    }

    private fun getFilteredItems(inList: MutableList<PumpHistoryEntry>?, entryTypes: Set<PumpHistoryEntryType>?): MutableList<PumpHistoryEntry> {
        val outList: MutableList<PumpHistoryEntry> = mutableListOf()
        if (!inList.isNullOrEmpty()) {
            for (pumpHistoryEntry in inList) {
                if (entryTypes.isNullOrEmpty()) {
                    outList.add(pumpHistoryEntry)
                } else {
                    if (entryTypes.contains(pumpHistoryEntry.entryType)) {
                        outList.add(pumpHistoryEntry)
                    }
                }
            }
        }
        return outList
    }

    private val logPrefix: String
        get() = "MedtronicHistoryData::"

    companion object {

        /**
         * Double bolus debug. We seem to have small problem with double Boluses (or sometimes also missing boluses
         * from history. This flag turns on debugging for that (default is off=false)... Debugging is pretty detailed,
         * so log files will get bigger.
         * Note: June 2020. Since this seems to be fixed, I am disabling this per default. I will leave code inside
         * in case we need it again. Code that turns this on is commented out RileyLinkMedtronicService#verifyConfiguration()
         */
        const val doubleBolusDebug = false
    }

}