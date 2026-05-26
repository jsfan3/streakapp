#import <Foundation/Foundation.h>

@interface net_informaticalibera_streakapp_native__AndroidUsageBridgeImpl : NSObject {
}

-(BOOL)launchPackage:(NSString*)param;
-(void)openUsageAccessSettings;
-(BOOL)isPackageLaunchable:(NSString*)param;
-(long long)getForegroundMillis:(NSString*)param param1:(long long)param1 param2:(long long)param2;
-(BOOL)isUsageAccessGranted;
-(BOOL)isSupported;
@end
