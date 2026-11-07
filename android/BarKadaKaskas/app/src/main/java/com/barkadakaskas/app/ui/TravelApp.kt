@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.barkadakaskas.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.barkadakaskas.app.domain.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

private val Forest = Color(0xFF214E3E)
private val Moss = Color(0xFF72883A)
private val Cream = Color(0xFFF7F8F4)
private val Ink = Color(0xFF172F2A)
private val colors = lightColorScheme(primary = Forest, secondary = Moss, background = Cream, surface = Color.White, onSurface = Ink, onBackground = Ink, primaryContainer = Color(0xFFE3EDD8), error = Color(0xFF9C3E2D))
private val transportOptions = listOf("" to "Starting point / decide later", "car" to "Car", "van" to "Van", "taxi" to "Taxi / ride-hailing", "motorcycle" to "Motorcycle", "tricycle" to "Tricycle", "walking" to "Walking", "bicycle" to "Bicycle", "transit" to "Public transit", "ferry" to "Ferry / boat", "flight" to "Flight", "other" to "Other")

fun amount(value: String): Long? = try { BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact().takeIf { it in 1..100000000 } } catch (_: Exception) { null }
fun money(value: Long, currency: String): String = NumberFormat.getCurrencyInstance(Locale("en", "PH")).apply { this.currency = Currency.getInstance(currency) }.format(BigDecimal.valueOf(value, 2))
private fun time(value: String, timezone: String): String = runCatching { OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of(timezone)).format(DateTimeFormatter.ofPattern("MMM d, h:mm a")) }.getOrElse { value }
private fun editTime(value: String, timezone: String): String = runCatching { OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of(timezone)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")) }.getOrElse { value.take(16) }

@Composable fun TravelApp(vm: TravelViewModel) {
    MaterialTheme(colorScheme = colors, shapes = Shapes(medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp))) {
        val context = LocalContext.current
        LaunchedEffect(vm.invitationUrl) { vm.invitationUrl?.let { url ->
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Join our trip on BarKadaKaskas: $url") }, "Invite your barkada")); vm.consumeInvitation()
        } }
        Surface(Modifier.fillMaxSize(), color = Cream) {
            if (vm.user == null) AuthScreen(vm) else if (vm.detail == null) TripsScreen(vm) else TripScreen(vm, vm.detail!!)
        }
    }
}

@Composable private fun ErrorNotice(vm: TravelViewModel) {
    vm.error?.let { message -> Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) { Text(message, color = MaterialTheme.colorScheme.onErrorContainer); TextButton(onClick = vm::clearError) { Text("Dismiss") } }
    } }
}
@Composable private fun PageColumn(content: @Composable ColumnScope.() -> Unit) = Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
@Composable private fun Eyebrow(text: String) { Text(text.uppercase(), color = Moss, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
@Composable private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); content() } } }
@Composable private fun EmptyState(title: String, description: String) { InfoCard(title) { Icon(Icons.Default.Explore, null, tint = Moss, modifier = Modifier.size(36.dp)); Text(description, color = Color(0xFF738178)) } }
@Composable private fun Field(label: String, value: String, change: (String) -> Unit, password: Boolean = false, number: Boolean = false) {
    OutlinedTextField(value, change, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None, keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else if (number) KeyboardType.Decimal else KeyboardType.Text))
}
@Composable private fun Choice(label: String, value: String, options: List<Pair<String, String>>, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: ${options.find { it.first == value }?.second ?: "Choose"}", Modifier.weight(1f)); Icon(Icons.Default.ExpandMore, null) }
        DropdownMenu(expanded, { expanded = false }) { options.forEach { (key, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { change(key); expanded = false }) } }
    }
}
@Composable private fun Submit(label: String, busy: Boolean, enabled: Boolean = true, click: () -> Unit) { Button(click, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled && !busy) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp) else Text(label) } }

@Composable private fun AuthScreen(vm: TravelViewModel) {
    var register by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf(vm.serverUrl) }
    Scaffold { padding -> Box(Modifier.padding(padding)) { PageColumn {
        Spacer(Modifier.height(16.dp)); Icon(Icons.Default.Explore, null, tint = Forest, modifier = Modifier.size(50.dp))
        Text("BarKadaKaskas", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold)
        Text("Good trips. Great company. Fair shares.", color = Moss)
        Spacer(Modifier.height(12.dp)); Text(if (register) "Start your next adventure" else "Welcome back", style = MaterialTheme.typography.headlineSmall)
        ErrorNotice(vm)
        if (register) Field("Your name", name, { name = it })
        Field("Email", email, { email = it }); Field("Password", password, { password = it }, password = true)
        Field("Server URL", server, { server = it })
        Text("Use your deployed HTTPS server. For emulator testing use a reachable development server, not the computer-only .test hostname.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Submit(if (register) "Create account" else "Log in", vm.busy, email.isNotBlank() && password.isNotBlank() && server.isNotBlank() && (!register || name.isNotBlank())) { vm.authenticate(server, name, email, password, register) }
        TextButton(onClick = { register = !register; vm.clearError() }, enabled = !vm.busy) { Text(if (register) "Already have an account? Log in" else "New here? Create an account") }
    } } }
}

@Composable private fun TripsScreen(vm: TravelViewModel) {
    var create by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("My trips", fontWeight = FontWeight.Bold) }, actions = { IconButton(vm::refresh, enabled = !vm.busy) { Icon(Icons.Default.Refresh, "Refresh trips") }; IconButton(vm::signOut, enabled = !vm.busy) { Icon(Icons.Default.Logout, "Log out") } }) }, floatingActionButton = { ExtendedFloatingActionButton(onClick = { create = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Plan a trip") }) }) { padding -> Box(Modifier.padding(padding)) { PageColumn {
        Eyebrow("YOUR NEXT CHAPTER"); Text("Where to, ${vm.user?.name?.substringBefore(' ')}?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Keep your plans and shared expenses together.", color = Color.Gray); ErrorNotice(vm)
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.trips.isEmpty() && !vm.busy) EmptyState("Your adventures start here", "Create a trip to invite your friends and split the spending.")
        vm.trips.forEach { trip -> Card(onClick = { vm.openTrip(trip) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow(trip.destination); Text(trip.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${trip.startsOn} — ${trip.endsOn}", color = Color.Gray); Text("${trip.memberCount} travelers · ${trip.currency}", style = MaterialTheme.typography.labelLarge, color = Forest)
        } } }; Spacer(Modifier.height(84.dp))
    } } }
    if (create) TripForm(vm) { create = false }
}

@Composable private fun TripScreen(vm: TravelViewModel, detail: TripDetail) {
    var tab by rememberSaveable(detail.trip.id) { mutableStateOf(0) }
    val labels = listOf("Itinerary", "Expenses", "Balances", "Group")
    val icons = listOf(Icons.Default.Map, Icons.Default.ReceiptLong, Icons.Default.AccountBalanceWallet, Icons.Default.Groups)
    Scaffold(topBar = { TopAppBar(title = { Text(detail.trip.name, maxLines = 1) }, navigationIcon = { IconButton(vm::backToTrips, enabled = !vm.busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to trips") } }, actions = { IconButton(vm::refresh, enabled = !vm.busy) { Icon(Icons.Default.Refresh, "Refresh trip") } }) }, bottomBar = { NavigationBar { labels.forEachIndexed { index, label -> NavigationBarItem(tab == index, { tab = index }, icon = { Icon(icons[index], label) }, label = { Text(label) }) } } }) { padding -> Box(Modifier.padding(padding)) { PageColumn {
        Eyebrow(detail.trip.destination); Text("${detail.trip.startsOn} — ${detail.trip.endsOn}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        ErrorNotice(vm); if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        when (tab) { 0 -> Itinerary(vm, detail); 1 -> Expenses(vm, detail); 2 -> Balances(vm, detail); 3 -> Group(vm, detail) }
    } } }
}

@Composable private fun Itinerary(vm: TravelViewModel, detail: TripDetail) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Stop?>(null) }; var form by remember { mutableStateOf(false) }
    Text("The plan", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text("Times in ${detail.trip.timezone}", style = MaterialTheme.typography.bodySmall)
    if (detail.isOrganizer) OutlinedButton(onClick = { editing = null; form = true }, enabled = !vm.busy) { Icon(Icons.Default.Add, null); Text("Add a stop") }
    if (detail.stops.isEmpty()) EmptyState("A blank itinerary", "Add your arrival point, then the places you want to visit.")
    detail.stops.sortedBy { it.startsAt }.forEachIndexed { index, stop -> InfoCard("${index + 1}. ${stop.title}") {
        Text(time(stop.startsAt, detail.trip.timezone), color = Moss, fontWeight = FontWeight.SemiBold)
        Text(stop.location.ifBlank { "Location to follow" }); if (stop.description.isNotBlank()) Text(stop.description)
        Text(if (index == 0) "Starting point" else "Transport: ${transportOptions.find { it.first == stop.transport }?.second ?: "To be decided"}", style = MaterialTheme.typography.bodySmall)
        Text("Until ${time(stop.endsAt, detail.trip.timezone)}", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        Row { if (stop.location.isNotBlank()) TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(stop.location)}"))) }) { Text("Open in Maps") }
            if (detail.isOrganizer) TextButton(onClick = { editing = stop; form = true }, enabled = !vm.busy) { Text("Edit stop") }
        }
    } }
    if (form) StopForm(vm, detail, editing) { form = false }
}

@Composable private fun Expenses(vm: TravelViewModel, detail: TripDetail) {
    var create by remember { mutableStateOf(false) }; var selectedId by remember { mutableStateOf<Long?>(null) }
    Text("Shared expenses", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    val total = detail.expenses.filter { it.status in listOf("completed", "closed") }.sumOf { it.totalMinor }
    Card(colors = CardDefaults.cardColors(containerColor = Forest), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(22.dp)) { Text("Trip spending", color = Color(0xFFCDDEBC)); Text(money(total, detail.trip.currency), color = Color.White, style = MaterialTheme.typography.headlineLarge); Text("Completed and closed expenses", color = Color(0xFFCDDEBC), style = MaterialTheme.typography.bodySmall) } }
    OutlinedButton(onClick = { create = true }, enabled = !vm.busy) { Icon(Icons.Default.Add, null); Text("Add expense") }
    if (detail.expenses.isEmpty()) EmptyState("No expenses yet", "Record your first shared bill to keep everyone on the same page.")
    detail.expenses.forEach { expense -> Card(onClick = { selectedId = expense.id }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(expense.title, Modifier.weight(1f), fontWeight = FontWeight.Bold); Text(money(expense.totalMinor, detail.trip.currency), fontWeight = FontWeight.Bold) }
        Text("${expense.category} · ${expense.merchant}", style = MaterialTheme.typography.bodySmall, color = Color.Gray); Status(expense.status)
    } } }
    if (create) ExpenseForm(vm, detail) { create = false }
    detail.expenses.find { it.id == selectedId }?.let { ExpenseSheet(vm, detail, it) { selectedId = null } }
}

@Composable private fun Status(status: String) { Surface(color = if (status == "cancelled") Color(0xFFFCE8E3) else Color(0xFFEDF2E6), shape = RoundedCornerShape(8.dp)) { Text(status.replaceFirstChar { it.uppercase() }, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium) } }

@Composable private fun Balances(vm: TravelViewModel, detail: TripDetail) {
    var form by remember { mutableStateOf(false) }
    Text("Keep it fair", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text("Reimbursements count once confirmed. Recording a payment does not send money.", color = Color.Gray)
    detail.balances.forEach { balance -> InfoCard(balance.name) { Text(when { balance.netMinor > 0 -> "Receives "; balance.netMinor < 0 -> "Owes "; else -> "Settled " } + money(kotlin.math.abs(balance.netMinor), detail.trip.currency), color = if (balance.netMinor < 0) MaterialTheme.colorScheme.error else Forest, fontWeight = FontWeight.Bold) } }
    if (detail.members.size > 1) OutlinedButton(onClick = { form = true }, enabled = !vm.busy) { Text("Record money you sent") }
    Text("Payment history", style = MaterialTheme.typography.titleLarge)
    if (detail.settlements.isEmpty()) Text("No reimbursements recorded yet.", color = Color.Gray)
    detail.settlements.forEach { payment -> val sender = detail.members.find { it.id == payment.fromMemberId }?.name ?: "Traveler"; val recipient = detail.members.find { it.id == payment.toMemberId }?.name ?: "Traveler"
        InfoCard("$sender ${if (payment.status == "pending") "reported paying" else "paid"} $recipient") {
            Text("${money(payment.amountMinor, detail.trip.currency)} using ${payment.method.replace('_', ' ')}")
            val expense = detail.expenses.find { it.id == payment.expenseId }; Text(expense?.let { "For ${it.category} [${it.title}] on ${it.merchant}" } ?: "General trip reimbursement", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            Status(payment.status)
            if (payment.status == "pending" && payment.toMemberId == detail.currentMemberId) Button(onClick = { vm.mutate { repo, current -> repo.confirmSettlement(current.trip.id, payment.id) } }, enabled = !vm.busy) { Text("Confirm I received ${money(payment.amountMinor, detail.trip.currency)}") }
        }
    }
    if (form) SettlementForm(vm, detail) { form = false }
}

@Composable private fun Group(vm: TravelViewModel, detail: TripDetail) {
    Text("Your barkada", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text("${detail.members.size} travelers · ${if (detail.isOrganizer) "You organize this trip" else "Trip member"}", color = Color.Gray)
    detail.members.forEach { member -> InfoCard(member.name) { Text(if (member.id == detail.currentMemberId) "You" else "Traveler", color = Moss) } }
    if (detail.isOrganizer) Submit("Invite friends", vm.busy, click = vm::invite)
}

@Composable private fun FormSheet(title: String, vm: TravelViewModel, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = { if (!vm.busy) dismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); ErrorNotice(vm); content(); TextButton(onClick = dismiss, enabled = !vm.busy) { Text("Cancel") } }
    }
}

@Composable private fun TripForm(vm: TravelViewModel, dismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }; var destination by rememberSaveable { mutableStateOf("") }; var start by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }; var end by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }; var currency by rememberSaveable { mutableStateOf("PHP") }; var zone by rememberSaveable { mutableStateOf("Asia/Manila") }
    FormSheet("Plan a trip", vm, dismiss) {
        Field("Trip name", name, { name = it }); Field("Destination", destination, { destination = it }); Field("Starts · YYYY-MM-DD", start, { start = it }); Field("Ends · YYYY-MM-DD", end, { end = it }); Choice("Currency", currency, listOf("PHP", "USD", "EUR", "GBP", "SGD", "AUD", "CAD").map { it to it }, { currency = it }); Field("Timezone", zone, { zone = it })
        Submit("Create trip", vm.busy, name.isNotBlank() && destination.isNotBlank()) { vm.createTrip(TripDraft(name, destination, start, end, currency, zone), dismiss) }
    }
}

@Composable private fun StopForm(vm: TravelViewModel, detail: TripDetail, stop: Stop?, dismiss: () -> Unit) {
    var title by rememberSaveable(stop?.id) { mutableStateOf(stop?.title ?: "") }; var location by rememberSaveable(stop?.id) { mutableStateOf(stop?.location ?: "") }; var notes by rememberSaveable(stop?.id) { mutableStateOf(stop?.description ?: "") }; var start by rememberSaveable(stop?.id) { mutableStateOf(stop?.let { editTime(it.startsAt, detail.trip.timezone) } ?: "${detail.trip.startsOn}T09:00") }; var end by rememberSaveable(stop?.id) { mutableStateOf(stop?.let { editTime(it.endsAt, detail.trip.timezone) } ?: "${detail.trip.startsOn}T10:00") }; var transport by rememberSaveable(stop?.id) { mutableStateOf(stop?.transport ?: "") }
    FormSheet(if (stop == null) "Add a stop" else "Edit stop", vm, dismiss) {
        Field("Activity", title, { title = it }); Field("Location / full address", location, { location = it }); Field("Notes", notes, { notes = it }); Choice("Transport from previous stop", transport, transportOptions, { transport = it }); Text("Times in ${detail.trip.timezone} · YYYY-MM-DDTHH:mm", style = MaterialTheme.typography.bodySmall); Field("Starts", start, { start = it }); Field("Ends", end, { end = it })
        Submit("Save stop", vm.busy, title.isNotBlank()) { vm.mutate(dismiss) { repo, current -> repo.saveStop(current.trip.id, stop?.id, StopDraft(title, location, notes, start, end, transport.ifBlank { null })) } }
    }
}

@Composable private fun ExpenseForm(vm: TravelViewModel, detail: TripDetail, dismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }; var merchant by rememberSaveable { mutableStateOf("") }; var total by rememberSaveable { mutableStateOf("") }; var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }; var category by rememberSaveable { mutableStateOf(detail.categories.firstOrNull()?.id?.toString() ?: "") }; var payer by rememberSaveable { mutableStateOf("") }; var selected by remember { mutableStateOf(detail.members.map { it.id }.toSet()) }
    FormSheet("Add shared expense", vm, dismiss) {
        Field("Description", title, { title = it }); Field("Merchant", merchant, { merchant = it }); Choice("Expense type", category, detail.categories.map { it.id.toString() to it.name }, { category = it }); Field("Total (${detail.trip.currency})", total, { total = it }, number = true); Field("Date · YYYY-MM-DD", date, { date = it }); Choice("Who covered the full bill?", payer, listOf("" to "Not paid yet") + detail.members.map { it.id.toString() to it.name }, { payer = it })
        Text("Split equally between", fontWeight = FontWeight.Bold)
        detail.members.forEach { member -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(member.id in selected, { selected = if (it) selected + member.id else selected - member.id }); Text(member.name) } }
        amount(total)?.let { cents -> if (selected.isNotEmpty()) Text("About ${money(cents / selected.size, detail.trip.currency)} each. The server allocates remaining cents fairly.", style = MaterialTheme.typography.bodySmall) }
        Submit("Save expense", vm.busy, title.isNotBlank() && category.isNotBlank() && amount(total) != null && selected.isNotEmpty()) { vm.mutate(dismiss) { repo, current -> repo.createExpense(current.trip.id, ExpenseDraft(title, merchant, category.toLong(), amount(total)!!, date, selected.toList(), payer.toLongOrNull())) } }
    }
}

@Composable private fun ExpenseSheet(vm: TravelViewModel, detail: TripDetail, expense: Expense, dismiss: () -> Unit) {
    var action by remember { mutableStateOf<String?>(null) }; var reason by remember { mutableStateOf("") }; var voidPayments by remember { mutableStateOf(false) }; var selectedShare by remember { mutableStateOf<Share?>(null) }; var source by remember { mutableStateOf("") }; var payer by remember { mutableStateOf(detail.currentMemberId.toString()) }; var paymentAmount by remember { mutableStateOf("") }
    FormSheet(expense.title, vm, dismiss) {
        Text(money(expense.totalMinor, detail.trip.currency), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Status(expense.status); Text("${expense.category} · ${expense.merchant}")
        Text("Assigned shares", fontWeight = FontWeight.Bold)
        expense.shares.forEach { share -> val member = detail.members.find { it.id == share.memberId }; Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(member?.name ?: "Traveler"); Text(money(share.amountMinor, detail.trip.currency), color = Forest); Text(if (share.remainingMinor <= 0) "Paid / covered" else "Remaining ${money(share.remainingMinor, detail.trip.currency)}", style = MaterialTheme.typography.bodySmall) }
            val mayConfirm = detail.isOrganizer || expense.payments.any { it.memberId == detail.currentMemberId }
            if (mayConfirm && share.remainingMinor > 0 && expense.status in listOf("open", "completed", "closed")) TextButton(onClick = { selectedShare = share; source = "" }, enabled = !vm.busy) { Text("Mark paid") }
        } }
        Text("Paid to merchant", fontWeight = FontWeight.Bold)
        expense.payments.forEach { Text("${detail.members.find { m -> m.id == it.memberId }?.name ?: "Traveler"} · ${money(it.amountMinor, detail.trip.currency)}") }
        if (expense.payments.isEmpty()) Text("No merchant payment recorded.")
        if (detail.isOrganizer) {
            if (expense.status == "open") { OutlinedButton(onClick = { action = "payment" }) { Text("Record merchant payment") }; Button(onClick = { action = "completed" }) { Text("Complete merchant bill") } }
            if (expense.status == "completed") Button(onClick = { action = "closed" }) { Text("Close expense") }
            if (expense.status in listOf("open", "completed")) TextButton(onClick = { action = "cancelled" }) { Text("Cancel expense", color = MaterialTheme.colorScheme.error) }
        }
        selectedShare?.let { share ->
            HorizontalDivider(); Text("Confirm payment for ${detail.members.find { it.id == share.memberId }?.name}", fontWeight = FontWeight.Bold)
            val existing = detail.settlements.filter { it.expenseId == null && it.fromMemberId == share.memberId && it.status in listOf("pending", "confirmed") }
            Choice("Payment to apply", source, listOf("" to "Choose payment", "new" to "A separate new payment") + existing.map { it.id.toString() to "Existing ${money(it.amountMinor, detail.trip.currency)} to ${detail.members.find { m -> m.id == it.toMemberId }?.name}" }, { source = it })
            Text("Only confirm money actually received. This records a reimbursement; it does not transfer funds.", style = MaterialTheme.typography.bodySmall)
            Submit("Confirm received", vm.busy, source.isNotBlank()) { vm.mutate({ selectedShare = null }) { repo, current -> repo.markSharePaid(current.trip.id, expense, share.memberId, source.toLongOrNull(), source == "new") } }
        }
        action?.let { target ->
            HorizontalDivider()
            when (target) {
                "payment" -> { Choice("Paid by", payer, detail.members.map { it.id.toString() to it.name }, { payer = it }); Field("Amount (${detail.trip.currency})", paymentAmount, { paymentAmount = it }, number = true); Submit("Record payment", vm.busy, amount(paymentAmount) != null) { vm.mutate({ action = null }) { repo, current -> repo.recordMerchantPayment(current.trip.id, expense, payer.toLong(), amount(paymentAmount)!!) } } }
                else -> {
                    Text(if (target == "closed") "Close this expense? Further expense edits will be locked." else if (target == "completed") "Confirm the merchant has been paid in full. Friends may still owe reimbursements." else "Cancel this expense? Its spending will be excluded from balances.")
                    if (target == "cancelled") { Field("Cancellation reason", reason, { reason = it }); if (expense.payments.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(voidPayments, { voidPayments = it }); Text("Void recorded merchant payments from balances") } }
                    Submit("Confirm ${target.replaceFirstChar { it.uppercase() }}", vm.busy, target != "cancelled" || reason.isNotBlank()) { vm.mutate({ action = null }) { repo, current -> repo.changeExpenseStatus(current.trip.id, expense, target, reason.ifBlank { null }, voidPayments) } }
                }
            }
        }
    }
}

@Composable private fun SettlementForm(vm: TravelViewModel, detail: TripDetail, dismiss: () -> Unit) {
    val recipients = detail.members.filter { it.id != detail.currentMemberId }; var recipient by rememberSaveable { mutableStateOf(recipients.firstOrNull()?.id?.toString() ?: "") }; var total by rememberSaveable { mutableStateOf("") }; var method by rememberSaveable { mutableStateOf("gcash") }
    FormSheet("Record money you sent", vm, dismiss) {
        Text("This records a repayment; it does not send money. The recipient confirms receipt.")
        Choice("Recipient", recipient, recipients.map { it.id.toString() to it.name }, { recipient = it }); Field("Amount (${detail.trip.currency})", total, { total = it }, number = true); Choice("Payment method", method, listOf("gcash" to "GCash", "cash" to "Cash", "bank_transfer" to "Bank transfer", "other" to "Other"), { method = it })
        Submit("Record reimbursement", vm.busy, recipient.isNotBlank() && amount(total) != null) { vm.mutate(dismiss) { repo, current -> repo.recordSettlement(current.trip.id, recipient.toLong(), amount(total)!!, method) } }
    }
}
