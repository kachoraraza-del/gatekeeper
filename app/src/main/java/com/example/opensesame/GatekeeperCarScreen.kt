package com.example.opensesame

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.car.app.model.Action

class GatekeeperCarScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        // Simple template showing status and a manual trigger button
        val itemList = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle("Gatekeeper Status")
                    .addText("Monitoring for Garage...")
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setTitle("Gatekeeper")
            .setSingleList(itemList)
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}