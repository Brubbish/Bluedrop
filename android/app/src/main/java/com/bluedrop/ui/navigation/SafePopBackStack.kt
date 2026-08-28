package com.bluedrop.ui.navigation

import androidx.navigation.NavController

fun NavController.safePopBackStack() {
    if (this.previousBackStackEntry != null) {
        this.popBackStack()
    }
}
