package com.example.wallettrackers.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

data class Category(
    val name: String,
    val icon: ImageVector,
    val color: Color,
    val subCategories: List<Category> = emptyList()
)

object Categories {
    private val foodAndDrinks = Category("Food & Drinks", Icons.Default.Fastfood, Color(0xFFF44336), listOf(
        Category("Groceries", Icons.Default.ShoppingCart, Color(0xFFF44336)),
        Category("Fast food", Icons.Default.Fastfood, Color(0xFFF44336)),
        Category("Restaurants", Icons.Default.Restaurant, Color(0xFFF44336)),
        Category("Cafe", Icons.Default.LocalCafe, Color(0xFFF44336)),
        Category("Snacks", Icons.Default.Icecream, Color(0xFFF44336)),
        Category("Bakery", Icons.Default.BakeryDining, Color(0xFFF44336)),
        Category("Juice & Smoothies", Icons.Default.LocalBar, Color(0xFFF44336)),
        Category("Food Delivery", Icons.Default.DeliveryDining, Color(0xFFF44336)),
    ))

    private val shopping = Category("Shopping", Icons.Default.ShoppingBag, Color(0xFF9C27B0), listOf(
        Category("Clothes", Icons.Default.Checkroom, Color(0xFF9C27B0)),
        Category("Electronics", Icons.Default.PhoneAndroid, Color(0xFF9C27B0)),
        Category("Accessories", Icons.Default.Watch, Color(0xFF9C27B0)),
        Category("Gifts", Icons.Default.CardGiftcard, Color(0xFF9C27B0)),
        Category("Health and beauty", Icons.Default.Spa, Color(0xFF9C27B0)),
        Category("Stationery, tools", Icons.Default.Edit, Color(0xFF9C27B0)),
        Category("Pharmacy", Icons.Default.LocalPharmacy, Color(0xFF9C27B0)),
        Category("Home & Decor", Icons.Default.Weekend, Color(0xFF9C27B0)),
    ))

    private val housing = Category("Housing", Icons.Default.House, Color(0xFF3F51B5), listOf(
        Category("Rent", Icons.Default.RealEstateAgent, Color(0xFF3F51B5)),
        Category("Electricity", Icons.Default.ElectricBolt, Color(0xFF3F51B5)),
        Category("Gas", Icons.Default.LocalFireDepartment, Color(0xFF3F51B5)),
        Category("Water", Icons.Default.WaterDrop, Color(0xFF3F51B5)),
        Category("Internet", Icons.Default.Wifi, Color(0xFF3F51B5)),
        Category("Mobile", Icons.Default.PhoneAndroid, Color(0xFF3F51B5)),
        Category("Maintenance, repairs", Icons.Default.Handyman, Color(0xFF3F51B5)),
        Category("Cleaning services", Icons.Default.CleaningServices, Color(0xFF3F51B5)),
    ))

    private val transportation = Category("Transportation", Icons.Default.DirectionsBus, Color(0xFF03A9F4), listOf(
        Category("Travel to another city", Icons.Default.Train, Color(0xFF03A9F4)),
        Category("Public transportation", Icons.Default.DirectionsBus, Color(0xFF03A9F4)),
        Category("Uber", Icons.Default.LocalTaxi, Color(0xFF03A9F4)),
        Category("Toktok", Icons.Default.TwoWheeler, Color(0xFF03A9F4)),
        Category("Travel abroad", Icons.Default.Flight, Color(0xFF03A9F4)),
    ))

    private val vehicle = Category("Vehicle", Icons.Default.DirectionsCar, Color(0xFF009688), listOf(
        Category("Fuel", Icons.Default.LocalGasStation, Color(0xFF009688)),
        Category("Parking", Icons.Default.LocalParking, Color(0xFF009688)),
        Category("Vehicle maintenance", Icons.Default.CarRepair, Color(0xFF009688)),
    ))

    private val lifeAndEntertainment = Category("Life & Entertainment", Icons.Default.TheaterComedy, Color(0xFFFF9800), listOf(
        Category("Sport & fitness", Icons.Default.FitnessCenter, Color(0xFFFF9800)),
        Category("Cigarettes", Icons.Default.SmokingRooms, Color(0xFFFF9800)),
        Category("Subscriptions", Icons.Default.Subscriptions, Color(0xFFFF9800)),
        Category("Courses", Icons.Default.School, Color(0xFFFF9800)),
        Category("Games", Icons.Default.VideogameAsset, Color(0xFFFF9800)),
        Category("Trips", Icons.Default.Hiking, Color(0xFFFF9800)),
        Category("Events", Icons.Default.Event, Color(0xFFFF9800)),
        Category("Hobbies", Icons.Default.Palette, Color(0xFFFF9800)),
        Category("Cinema & Theatre", Icons.Default.Movie, Color(0xFFFF9800)),
        Category("Books & Magazines", Icons.Default.MenuBook, Color(0xFFFF9800)),
    ))

    private val healthAndMedical = Category("Health & Medical", Icons.Default.LocalHospital, Color(0xFFE91E63), listOf(
        Category("Doctor", Icons.Default.LocalHospital, Color(0xFFE91E63)),
        Category("Medicine", Icons.Default.MedicalServices, Color(0xFFE91E63)),
        Category("Lab tests", Icons.Default.Biotech, Color(0xFFE91E63)),
        Category("Hospital", Icons.Default.MedicalServices, Color(0xFFE91E63)),
    ))

    private val education = Category("Education", Icons.Default.School, Color(0xFF795548), listOf(
        Category("School fees", Icons.Default.School, Color(0xFF795548)),
        Category("University fees", Icons.Default.AccountBalance, Color(0xFF795548)),
        Category("Tutoring", Icons.Default.People, Color(0xFF795548)),
    ))

    private val instapay = Category("Instapay", Icons.Default.AccountBalanceWallet, Color(0xFF2196F3), listOf(
        Category("Instapay income", Icons.Default.AddCircle, Color(0xFF2196F3)),
        Category("Instapay outcome", Icons.Default.RemoveCircle, Color(0xFF2196F3)),
    ))

    private val income = Category("Income", Icons.Default.AttachMoney, Color(0xFF4CAF50), listOf(
        Category("Salary", Icons.Default.Payments, Color(0xFF4CAF50)),
        Category("Lending", Icons.Default.CreditScore, Color(0xFF4CAF50)),
        Category("Renting", Icons.Default.RealEstateAgent, Color(0xFF4CAF50)),
        Category("Gifts", Icons.Default.CardGiftcard, Color(0xFF4CAF50)),
    ))

    private val credit = Category("Credit", Icons.Default.CreditCard, Color(0xFF673AB7), listOf(
        Category("Credit", Icons.Default.CreditCard, Color(0xFF673AB7))
    ))

    private val others = Category("Others", Icons.Default.MoreHoriz, Color(0xFF607D8B), listOf(
        Category("Others", Icons.Default.MoreHoriz, Color(0xFF607D8B))
    ))

    val list = listOf(
        foodAndDrinks,
        shopping,
        housing,
        transportation,
        vehicle,
        lifeAndEntertainment,
        healthAndMedical,
        education,
        instapay,
        income,
        credit,
        others
    )

    fun isIncomeCategory(categoryName: String): Boolean {
        return categoryName == "Income" || 
               categoryName == "Instapay income" || 
               income.subCategories.any { it.name == categoryName }
    }
}
