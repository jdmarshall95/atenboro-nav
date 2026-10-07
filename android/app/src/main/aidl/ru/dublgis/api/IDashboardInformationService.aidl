package ru.dublgis.api;

import ru.dublgis.api.IUpdateCallback;


interface IDashboardInformationService {
    //
    // Navigation information (API Version 0 and up)
    //

    // See: DashboardInformationConstants
    String getDashboardInformationJSON();
    void registerDashboardInformationCallback(IUpdateCallback callback);
    void unregisterDashboardInformationCallback(IUpdateCallback callback);


    // Not implemented for V0; if function call fails assume API version is 0.
    int getApiVersion();
}
